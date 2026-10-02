# Throwaway spike tool. One stage keeps the cgo build (H3 bindings) simple.
FROM golang:1.27
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN go build -o /usr/local/bin/bench .
ENTRYPOINT ["bench"]
