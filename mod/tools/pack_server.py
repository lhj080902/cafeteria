"""서버 리소스팩 호환성 테스트용 도우미.

테스트용 리소스팩(zip)을 만들고, HTTP Range(이어받기)를 지원하는 간단한 웹 서버로 내보낸다.
실제 서버들이 리소스팩을 내려 주는 방식(파일 호스팅에서 받기)을 흉내 낸 것이다.

사용법:
    python3 tools/pack_server.py --size-mb 12 --info pack-info.txt

--info 파일에 URL= 과 SHA1= 줄을 쓰고, 받은 요청은 표준 출력에 한 줄씩 남긴다.
--no-range 를 주면 Range 요청을 무시하고 항상 전체 파일을 보낸다 (이어받기를 지원하지 않는 호스팅 흉내).
"""
import argparse
import hashlib
import json
import os
import random
import re
import tempfile
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MARKER_KEY = "menu.singleplayer"
MARKER_VALUE = "CAFETERIA PACK TEST"


def build_pack(path: str, size_mb: int, seed: int) -> None:
    """적용됐는지 확인할 수 있는 번역 한 줄과, 크기를 채우는 압축 안 되는 파일을 넣은 리소스팩을 만든다.

    같은 seed 면 실행할 때마다 똑같은 팩(같은 해시)이 나온다. 게임을 껐다 켠 뒤에도 "같은 팩"으로 인식되는지 볼 때 필요하다.
    """
    fixed_time = (2020, 1, 1, 0, 0, 0)

    def add(pack, name, data):
        pack.writestr(zipfile.ZipInfo(name, fixed_time), data)

    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as pack:
        add(pack, "pack.mcmeta", json.dumps({"pack": {"pack_format": 46, "description": "Cafeteria test pack"}}))
        add(pack, "assets/minecraft/lang/en_us.json", json.dumps({MARKER_KEY: MARKER_VALUE}))
        add(pack, "assets/cafeteria_test/filler.bin", random.Random(seed).randbytes(size_mb * 1024 * 1024))


def make_handler(pack_path: str, support_range: bool):
    size = os.path.getsize(pack_path)
    etag = '"%s"' % hashlib.sha1(open(pack_path, "rb").read()).hexdigest()[:16]

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            if self.path.split("?")[0] != "/pack.zip":
                self.send_error(404)
                return

            requested = self.headers.get("Range")
            match = re.fullmatch(r"bytes=(\d+)-(\d*)", requested or "") if support_range else None
            start, end = 0, size - 1

            if match:
                start = int(match.group(1))
                end = min(size - 1, int(match.group(2))) if match.group(2) else size - 1
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            else:
                self.send_response(200)

            self.send_header("Content-Type", "application/zip")
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("ETag", etag)

            if support_range:
                self.send_header("Accept-Ranges", "bytes")

            self.end_headers()
            print(f"REQUEST range={requested or '-'} -> {206 if match else 200} bytes={end - start + 1}", flush=True)

            with open(pack_path, "rb") as pack:
                pack.seek(start)
                remaining = end - start + 1

                while remaining > 0:
                    chunk = pack.read(min(256 * 1024, remaining))

                    if not chunk:
                        break

                    try:
                        self.wfile.write(chunk)
                    except (BrokenPipeError, ConnectionResetError):
                        return

                    remaining -= len(chunk)

        def log_message(self, *args):
            pass

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--size-mb", type=int, default=12, help="팩 크기(MB). 나눠 받기 테스트는 8보다 커야 한다")
    parser.add_argument("--port", type=int, default=0, help="0 이면 빈 포트를 고른다")
    parser.add_argument("--info", required=True, help="URL 과 SHA1 을 적어 둘 파일")
    parser.add_argument("--no-range", action="store_true", help="Range 요청을 지원하지 않는 서버 흉내")
    parser.add_argument("--seed", type=int, default=1, help="팩 내용을 정하는 값. 같으면 같은 팩이 나온다")
    arguments = parser.parse_args()

    pack_path = os.path.join(tempfile.mkdtemp(prefix="cafeteria-pack-"), "pack.zip")
    build_pack(pack_path, arguments.size_mb, arguments.seed)
    sha1 = hashlib.sha1(open(pack_path, "rb").read()).hexdigest()
    server = ThreadingHTTPServer(("127.0.0.1", arguments.port), make_handler(pack_path, not arguments.no_range))

    with open(arguments.info, "w") as info:
        info.write(f"URL=http://127.0.0.1:{server.server_address[1]}/pack.zip\nSHA1={sha1}\n")

    print(f"SERVING {pack_path} ({os.path.getsize(pack_path)} bytes) on port {server.server_address[1]}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
