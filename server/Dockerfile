FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app

# Install the app + its runtime dependencies (asyncpg ships manylinux wheels, so no build
# toolchain is needed).
COPY pyproject.toml ./
COPY app ./app
COPY alembic ./alembic
COPY alembic.ini ./
RUN pip install --no-cache-dir .

# Bake the U2-Net weights into the image.
#
# rembg downloads them on first use otherwise, which would mean the first scan after every
# deploy stalls on a ~170 MB fetch — and fails outright on a host with no egress. Doing it at
# build time makes the container self-contained and the first scan as fast as the hundredth.
RUN python -c "from rembg import new_session; new_session('u2net')"

COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
# Strip any CR so a Windows (CRLF) checkout doesn't break the shebang (exit 127).
RUN sed -i 's/\r$//' /usr/local/bin/docker-entrypoint.sh \
    && chmod +x /usr/local/bin/docker-entrypoint.sh

EXPOSE 8000
ENTRYPOINT ["docker-entrypoint.sh"]
