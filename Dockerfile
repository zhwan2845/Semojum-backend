# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:21-jre

# HWP → PDF 변환 도구 (mode a HWP 지원, 2026-08-24)
# - pyhwp: HWP 5.x → ODT (자체 파서, 자바로 대체 불가)
# - libreoffice-writer: ODT → PDF 렌더링 (headless)
# - poppler-utils: pdftoppm — PDF 썸네일 첫 장 렌더(JPEG 2000 스캔본 지원, 2026-08-27)
# - fonts-noto-cjk·fonts-nanum: 한글 폰트 — 없으면 글자가 □로 렌더됨
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        python3 python3-pip \
        libreoffice-writer \
        poppler-utils \
        fonts-noto-cjk fonts-nanum \
    && pip3 install --no-cache-dir --break-system-packages pyhwp six \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# rhwp: HWP·HWPX → PDF (한글 조판대로 쪽을 나눈다). 실패하면 위 pyhwp·LibreOffice 경로가 이어 받는다
# - 버전을 고정하고 릴리스의 SHA256SUMS.txt 값으로 검증한다. 올릴 때는 실파일로 다시 시험할 것(1.0 전이라 동작이 자주 바뀐다)
ADD --checksum=sha256:f1e541a25cfe76b6bc1daf18bf9c1f9a5ac5e03e2c01b188daaf54107214f631 \
    https://github.com/edwardkim/rhwp/releases/download/v0.8.7/rhwp-v0.8.7-linux-x86_64.tar.gz /tmp/rhwp.tar.gz
# - tar 대신 python으로 푼다: 이 이미지의 tar는 x86 에뮬레이션(ARM Mac의 수동 배포 빌드)에서 지원하지 않는 시스템 호출을 써 실패한다
RUN python3 -c "import tarfile; tarfile.open('/tmp/rhwp.tar.gz').extractall('/opt', filter='data')" \
    && rm /tmp/rhwp.tar.gz && chmod 755 /opt/rhwp/rhwp \
    && mkdir -p /app && ln -s /opt/rhwp/rhwp /app/rhwp && /app/rhwp --version

WORKDIR /app
COPY scripts/hwp2odt.py hwp2odt.py
COPY build/libs/backend-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
