#!/usr/bin/env python3
"""将已构建的普通 JAR、独立 POM 和校验和打包；不构建、不上传、不请求网络。"""
from pathlib import Path
import hashlib
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def main():
    pom = ROOT / "pom.xml"
    version = ET.parse(pom).findtext("m:version", namespaces=NS)
    name = "agent-platform-" + version
    jar = ROOT / "target" / (name + ".jar")
    if not jar.is_file():
        raise SystemExit("请先执行 mvn package -DskipTests")
    entries = {name + ".jar": jar.read_bytes(), name + ".pom": pom.read_bytes()}
    checksums = "".join(hashlib.sha256(data).hexdigest() + "  " + filename + "\n" for filename, data in entries.items())
    bundle = ROOT / "target" / (name + "-release.zip")
    with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as archive:
        for filename, data in entries.items():
            archive.writestr(filename, data)
        archive.writestr("SHA256SUMS", checksums)
        archive.writestr("INSTALL.txt", "先执行 sha256sum -c SHA256SUMS，再执行：\n"
                         + "mvn install:install-file -Dfile=" + name + ".jar -DpomFile=" + name + ".pom\n"
                         + "安装用户须与构建宿主业务应用的用户一致。本包不含凭证，也不改变数据库。\n")
    print(bundle)


if __name__ == "__main__":
    main()
