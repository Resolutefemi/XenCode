# XenCloud API image. Build from the rust/ workspace root:
#   docker build -f crates/xencode-cloud/Dockerfile -t xencloud .

FROM rust:1-bookworm AS build
WORKDIR /build
COPY Cargo.toml Cargo.lock* ./
COPY crates ./crates
RUN cargo build --release -p xencode-cloud && \
    strip target/release/xencode-cloud

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && \
    rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/release/xencode-cloud /usr/local/bin/xencode-cloud
ENV XENCODE_BIND=0.0.0.0:8080
EXPOSE 8080
ENTRYPOINT ["xencode-cloud"]
