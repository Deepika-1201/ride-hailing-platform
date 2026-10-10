# syntax=docker/dockerfile:1.7
# The simulator (ADR-021). h3-go needs cgo, so the runtime image has glibc.
FROM golang:1.27 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=1 go build -trimpath -ldflags="-s -w" -o /out/sim ./cmd/sim

FROM debian:trixie-slim
RUN useradd --system --create-home --home-dir /sim sim && install -d -o sim /sim/state
WORKDIR /sim
COPY --from=build /out/sim /usr/local/bin/sim
COPY scenarios scenarios
COPY testdata testdata
USER sim
ENTRYPOINT ["sim"]
CMD ["run", "-scenario", "scenarios/ci.yaml", "-routes", "testdata/routes/ci.json"]
