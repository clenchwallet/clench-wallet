#!/usr/bin/env python3
"""Experimental pinned SQLCipher core replacement; never changes app dependencies."""
import argparse, hashlib, json, os, platform, re, shutil, subprocess, tarfile, urllib.request, zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
INPUTS = json.loads(Path(__file__).with_name('sqlcipher-inputs.json').read_text())
def sha(p): return hashlib.file_digest(Path(p).open('rb'), 'sha256').hexdigest()
def run(args, **kw): return subprocess.check_output([str(x) for x in args], text=True, **kw)
def download(url, path, digest):
    if not path.exists():
        with urllib.request.urlopen(url, timeout=120) as reply: path.write_bytes(reply.read())
    if sha(path) != digest: raise ValueError('Input checksum mismatch: '+path.name)
    return path

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    if (platform.system(),platform.machine()) != ('Linux','x86_64'): raise SystemExit('Linux x86_64 required')
    out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
    if (out/'provenance.json').exists(): raise SystemExit('Use a fresh output directory')
    env=os.environ.copy()
    for key in list(env):
        if key.startswith(('CFLAGS','CPPFLAGS','CXXFLAGS','LDFLAGS','SQLCIPHER_','NDK_','APP_')) or key in ['CC','CXX','AR','LD','MAKEFLAGS']: del env[key]
    env.update(LC_ALL='C',TZ='UTC',SOURCE_DATE_EPOCH='1788825600')
    roots={}
    for name,spec in INPUTS.items():
        if not isinstance(spec,dict) or 'url' not in spec: continue
        archive=download(spec['url'],out/(name+'.tar.gz'),spec['sha256'])
        dest=out/name;dest.mkdir()
        with tarfile.open(archive) as tar: tar.extractall(dest,filter='data')
        roots[name]=next(dest.iterdir())
    core=roots['core'];android=roots['android'];jni=android/'sqlcipher/src/main/jni'
    # Generate from the explicitly selected core, never the Android tag's stale gitlink.
    with (out/'generation.log').open('w') as log:
        subprocess.run(['./configure','--with-tempstore=yes','--disable-tcl'],cwd=core,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
        subprocess.run(['make','sqlite3.c'],cwd=core,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    for name,digest in INPUTS['generated'].items():
        if sha(core/name)!=digest: raise ValueError('Generated input drift: '+name)
        shutil.copyfile(core/name,jni/'sqlcipher'/name)
    target=jni/'libtomcrypt/src'
    if target.exists(): shutil.rmtree(target)
    shutil.copytree(roots['libtomcrypt'],target)
    ndk=Path(os.environ['ANDROID_HOME'])/'ndk'/INPUTS['ndk_version'];llvm=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
    if 'Pkg.Revision = '+INPUTS['ndk_version'] not in (ndk/'source.properties').read_text():raise ValueError('NDK drift')
    expected=json.loads((ROOT/'docs/security/upstream/bdk-ffi-3.0.0-clench-provenance.json').read_text())['clang_sha256']
    if sha(llvm/'clang')!=expected:raise ValueError('Pinned NDK compiler drift')
    # Preserve the reviewed Android JNI default defines, with deterministic path remapping.
    makefile=(jni/'sqlcipher/Android.mk').read_text();defs=re.findall(r'-D[A-Z0-9_]+(?:=[A-Za-z0-9_]+)?',makefile.split('endif')[0])
    flags=' '.join(defs+[f'-ffile-prefix-map={out}=/sqlcipher-build',f'-ffile-prefix-map={ndk}=/ndk'])
    command=[str(ndk/'ndk-build'),'NDK_PROJECT_PATH=null','APP_BUILD_SCRIPT='+str(jni/'Android.mk'),'NDK_APPLICATION_MK='+str(jni/'Application.mk'),'APP_ABI=arm64-v8a armeabi-v7a x86_64','APP_PLATFORM=android-23','APP_OPTIM=release','NDK_DEBUG=0','NDK_OUT='+str(out/'obj'),'NDK_LIBS_OUT='+str(out/'libs'),'SQLCIPHER_CFLAGS='+flags,'-j4','V=1']
    with (out/'compile.log').open('w') as log:subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    vendor=download('https://repo.maven.apache.org/maven2/net/zetetic/sqlcipher-android/4.17.0/sqlcipher-android-4.17.0.aar',out/'vendor.aar','44fc40c33d1de597c8339072a71fa0ff20e12d01ab352d6abe4ad5df668ead94')
    abi={}
    def exports(path): return sorted(line.split()[0] for line in run([llvm/'llvm-nm','--dynamic','--defined-only','--format=posix',path]).splitlines() if line.strip())
    with zipfile.ZipFile(vendor) as z:
        for arch in ['arm64-v8a','armeabi-v7a','x86_64']:
            lib=out/'libs'/arch/'libsqlcipher.so';old=out/(arch+'-vendor.so');old.write_bytes(z.read('jni/'+arch+'/libsqlcipher.so'))
            prev=set(exports(old));new=set(exports(lib));metadata=run([llvm/'llvm-readelf','--wide','--program-headers','--dynamic',lib]);(out/(arch+'-readelf.txt')).write_text(metadata)
            loads=[x for x in metadata.splitlines() if x.strip().startswith('LOAD ')];assert loads and all(int(x.split()[-1],16)>=16384 for x in loads)
            assert 'GNU_RELRO' in metadata and 'BIND_NOW' in metadata and 'TEXTREL' not in metadata
            abi[arch]={'sha256':sha(lib),'removed_exports':sorted(prev-new),'added_exports':sorted(new-prev),'exports_sha256':hashlib.sha256(('\n'.join(sorted(new))+'\n').encode()).hexdigest()}
            if prev-new:raise ValueError('Removed JNI/native exports: '+arch)
    report={'inputs':INPUTS,'abi':abi,'compiler_sha256':sha(llvm/'clang'),'compiler':run([llvm/'clang','--version']),'command':command,'host':platform.platform(),'generator_tools':{'make':run(['make','--version']).splitlines()[0],'cc':run(['cc','--version']).splitlines()[0]},'scope':'Experimental4.19core with4.17JNI wrapper; not integrated, reviewed or runtime accepted. No reproduction claim for vendor binary.'}
    (out/'provenance.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(abi,indent=2))
if __name__=='__main__':main()
