#!/usr/bin/env python3
"""Package independently reproduced SQLCipher JNI with unchanged 4.17 Android bindings."""
import argparse,hashlib,json,shutil,subprocess,sys,tempfile,urllib.request,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
VERSION='4.19.0-clench.1'
PINS={'aar':'44fc40c33d1de597c8339072a71fa0ff20e12d01ab352d6abe4ad5df668ead94','module':'d6a51f3d869179fb2ab5f1968a51884a127823bcd3ccb1f070d1f0ddeae62537','pom':'219809124475bc3550d31805146057bf230fc444e8a062d1ee0599bd7e163edb'}
JNI={'arm64-v8a':'416e13c33e3955958778fb1c393bf8591d74f1a649126d71c06975eacd90fe13','armeabi-v7a':'5ed6a35872886dd3ae864016c5907180611c12da62a240adbf864a6cc88473ef','x86_64':'147127560063c9c6e0fa24104d6485dfb2fa1ded2137784b4d2ab291225c7d26'}
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--package-only',action='store_true');args=p.parse_args()
    build=ROOT/'build/native-sqlcipher';build.mkdir(parents=True,exist_ok=True)
    if not args.package_only:
        out=Path(tempfile.mkdtemp(prefix='source-',dir=build))
        subprocess.run([sys.executable,str(ROOT/'scripts/native/build-sqlcipher-candidate.py'),'--output',str(out)],check=True)
        for abi in JNI:
            dest=build/'jni'/abi/'libsqlcipher.so';dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(out/'libs'/abi/'libsqlcipher.so',dest)
        shutil.copyfile(out/'provenance.json',build/'provenance.json')
    for abi,digest in JNI.items():
        if sha(build/'jni'/abi/'libsqlcipher.so')!=digest:raise ValueError('Rebuilt SQLCipher drift: '+abi)
    inputs={}
    for ext,digest in PINS.items():
        path=build/f'vendor.{ext}'
        if not path.exists():
            with urllib.request.urlopen(f'https://repo.maven.apache.org/maven2/net/zetetic/sqlcipher-android/4.17.0/sqlcipher-android-4.17.0.{ext}',timeout=60) as r:path.write_bytes(r.read())
        if sha(path)!=digest:raise ValueError('Vendor wrapper input drift: '+ext)
        inputs[ext]=path
    output=build/f'maven/net/zetetic/sqlcipher-android/{VERSION}';output.mkdir(parents=True,exist_ok=True);stem=f'sqlcipher-android-{VERSION}';aar=output/(stem+'.aar')
    preserved={}
    with zipfile.ZipFile(inputs['aar']) as old,zipfile.ZipFile(aar,'w',compression=zipfile.ZIP_STORED) as new:
        names=old.namelist()
        if len(names)!=len(set(names)):raise ValueError('Duplicate vendor entries')
        expected={f'jni/{a}/libsqlcipher.so' for a in [*JNI,'x86']}
        if {n for n in names if n.endswith('.so')}!=expected:raise ValueError('Unexpected native entry set')
        for n in sorted(set(n for n in names if not n.endswith('/') and not n.startswith('jni/'))|{f'jni/{a}/libsqlcipher.so' for a in JNI}):
            data=(build/n).read_bytes() if n.startswith('jni/') else old.read(n)
            if not n.startswith('jni/'):preserved[n]=hashlib.sha256(data).hexdigest()
            info=zipfile.ZipInfo(n,date_time=(1980,1,1,0,0,0));info.create_system=3;info.external_attr=0o100644<<16;new.writestr(info,data)
    pom=inputs['pom'].read_text();assert pom.count('<version>4.17.0</version>')==1
    (output/(stem+'.pom')).write_text(pom.replace('<version>4.17.0</version>',f'<version>{VERSION}</version>'))
    module=json.loads(inputs['module'].read_text());assert module['component']['version']=='4.17.0';module['component']['version']=VERSION
    module['variants']=[v for v in module['variants'] if v['attributes']['org.gradle.category']!='documentation']
    for v in module['variants']:
        assert len(v['files'])==1 and v['files'][0]['name']=='sqlcipher-android-4.17.0.aar'
        v['files']=[{'name':aar.name,'url':aar.name,'size':aar.stat().st_size,'sha256':sha(aar)}]
    (output/(stem+'.module')).write_text(json.dumps(module,indent=2)+'\n')
    report={'coordinate':f'net.zetetic:sqlcipher-android:{VERSION}','outputs':{p.name:sha(p) for p in output.iterdir()},'jni':JNI,'unchanged_wrapper_entries':preserved,'omitted_vendor_abi':'x86 is not a shipped Clench ABI; no old vendor JNI retained','scope':'Pinned source-built core4.19/AndroidJNI4.17/LTC fork; app acceptance and independent review required'}
    (build/'packaging-manifest.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if __name__=='__main__':main()
