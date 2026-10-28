variable "VERSION" {
  default = "local"
}

variable "JAR" {
  default = "faultora-cli/target/faultora-${VERSION}.jar"
}

variable "IMAGE" {
  default = "faultora/runner"
}

variable "OUTPUT" {
  default = "dist/faultora-runner-${VERSION}.oci.tar"
}

variable "REVISION" {
  default = "unknown"
}

variable "SOURCE" {
  default = "https://github.com/Malatiel/Faultora"
}

group "default" {
  targets = ["local"]
}

target "image" {
  context = "."
  dockerfile = "Dockerfile"
  args = {
    JAR = JAR
    VERSION = VERSION
  }
  labels = {
    "org.opencontainers.image.revision" = REVISION
    "org.opencontainers.image.source" = SOURCE
  }
  platforms = ["linux/amd64", "linux/arm64"]
  tags = ["${IMAGE}:${VERSION}"]
}

# The default is a file, never a registry write or a multi-platform --load.
target "local" {
  inherits = ["image"]
  output = ["type=oci,dest=${OUTPUT}"]
}

# Select one platform for a runtime check; no QEMU installation is implicit.
target "load" {
  inherits = ["image"]
  platforms = ["linux/amd64"]
  output = ["type=docker"]
}
