package dev.faultora.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in qualification of runner image installation on an offline recipient. */
class OfflineRunnerImageCheck {
    private static final String RECEIVER_IMAGE =
            "docker.io/library/docker:28.5.2-dind@sha256:2a232a42256f70d78e3cc5d2b5d6b3276710a0de0596c145f627ecfae90282ac";
    private static final String RECEIVER_SOCKET = "--host=unix:///var/run/docker.sock";
    // Containerd and classic Docker stores report different image IDs for the same image.
    private static final String IMAGE_CONTENTS = "{{.Os}}/{{.Architecture}} {{json .RootFS.Layers}} "
            + "{{json .Config.User}} {{json .Config.WorkingDir}} {{json .Config.Entrypoint}} "
            + "{{json .Config.Cmd}} {{json .Config.Env}}";

    private final Path project = Path.of(System.getProperty("faultora.projectRoot")).toAbsolutePath().normalize();

    @TempDir
    Path directory;

    @Test
    void theTransferredBundleStartsTheRunnerImageOnAnEmptyOfflineDockerHost() throws Exception {
        assertThat(System.getProperty("os.name")).as("the receiver check runs on Linux").isEqualTo("Linux");
        String version = System.getProperty("faultora.version");
        Path jar = Path.of(System.getProperty("faultora.cliJar")).toAbsolutePath().normalize();
        assertThat(jar).isRegularFile();
        String architecture = switch (run(30, "docker", "info", "--format", "{{.Architecture}}").strip()) {
            case "x86_64", "amd64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> throw new IllegalStateException("Unsupported image runtime architecture");
        };

        String id = UUID.randomUUID().toString();
        String image = "faultora/runner:offline-test-" + id;
        String receiver = "faultora-offline-receiver-" + id;
        try {
            run(300, "docker", "buildx", "bake", "-f", "deploy/runner-image.hcl", "load",
                    "--set", "load.args." + RunnerImageE2ETest.jarBuildArgument(project, jar),
                    "--set", "load.args.VERSION=" + version,
                    "--set", "load.platform=linux/" + architecture, "--set", "load.tags=" + image);
            String sourceContents = run(30, "docker", "image", "inspect", "--format", IMAGE_CONTENTS, image).strip();
            assertThat(sourceContents).startsWith("linux/" + architecture + " [").contains("sha256:");

            Path outbound = Files.createDirectory(directory.resolve("outbound"));
            String bundled = run(180, "env", "OUT=" + outbound, "IMAGE=" + image,
                    "sh", project.resolve("deploy/offline-bundle.sh").toString(), version);
            var fingerprint = Pattern.compile("SHA256SUMS fingerprint: ([0-9a-f]{64})").matcher(bundled);
            assertThat(fingerprint.find()).as(bundled).isTrue();
            String folder = "faultora-" + version + "-offline";
            Path archive = outbound.resolve(folder + ".tar.gz");

            try {
                run(45, "docker", "run", "--detach", "--privileged", "--init", "--network", "none",
                        "--pull", "never", "--name", receiver, "--entrypoint", "dockerd", RECEIVER_IMAGE,
                        RECEIVER_SOCKET, "--storage-driver=vfs", "--bridge=none",
                        "--iptables=false", "--ip6tables=false", "--ip-masq=false");
                String mode = run(30, "docker", "inspect", "--format", "{{.HostConfig.NetworkMode}}", receiver);
                assertThat(mode.strip()).isEqualTo("none");

                var ready = ImageCommand.run(project, directory, 45, "docker", "exec", receiver, "sh", "-ec", """
                        set -- /sys/class/net/*
                        test "$#" -eq 1
                        test "$1" = /sys/class/net/lo
                        for attempt in $(seq 30); do
                            if docker --host=unix:///var/run/docker.sock info >/dev/null 2>&1; then
                                images=$(docker --host=unix:///var/run/docker.sock image ls --all --quiet)
                                test -z "$images"
                                exit
                            fi
                            sleep 1
                        done
                        exit 1
                        """);
                if (ready.exitValue() != 0) {
                    throw new AssertionError("The offline receiver must start with only loopback and an empty store.\n"
                            + ready.output() + run(30, "docker", "logs", "--tail", "40", receiver));
                }

                run(120, "docker", "cp", archive.toString(), receiver + ":/tmp/bundle.tar.gz");
                String checked = run(120, "docker", "exec", receiver, "sh", "-ec", """
                        mkdir /tmp/transfer
                        tar -xzf /tmp/bundle.tar.gz -C /tmp/transfer
                        cd "/tmp/transfer/$1"
                        printf '%s  SHA256SUMS\\n' "$2" | sha256sum -c -
                        sha256sum -c SHA256SUMS
                        sh ./verify-offline-bundle.sh --self-consistency-only
                        docker --host=unix:///var/run/docker.sock image load --input "$3"
                        """, "offline-receiver", folder, fingerprint.group(1),
                        "faultora-runner-" + version + ".image.tar");
                assertThat(checked).contains("files match SHA256SUMS and nothing is unlisted");

                String loadedContents = run(30, "docker", "exec", receiver, "docker", RECEIVER_SOCKET,
                        "image", "inspect", "--format", IMAGE_CONTENTS, image).strip();
                assertThat(loadedContents).as("loaded platform, root filesystem layers and runtime settings")
                        .isEqualTo(sourceContents);

                String actualVersion = inRunnerImage(receiver, image, "--version");
                assertThat(actualVersion.strip()).isEqualTo("faultora " + version);
                assertThat(inRunnerImage(receiver, image, "runner", "--help")).contains("--dispatcher");
            } finally {
                remove("No such container", "docker", "container", "rm", "--force", "--volumes", receiver);
            }
        } finally {
            remove("No such image", "docker", "image", "rm", image);
        }
    }

    private String inRunnerImage(String receiver, String image, String... args) throws Exception {
        var command = new ArrayList<>(List.of("docker", "exec", receiver, "docker",
                RECEIVER_SOCKET, "run", "--rm", "--pull", "never", "--network", "none",
                "--read-only", "--cap-drop", "ALL", image));
        command.addAll(List.of(args));
        return run(60, command.toArray(String[]::new));
    }

    private String run(int seconds, String... command) throws Exception {
        var result = ImageCommand.run(project, directory, seconds, command);
        assertThat(result.exitValue()).as(result.output()).isZero();
        return result.output();
    }

    private void remove(String absent, String... command) throws Exception {
        var result = ImageCommand.run(project, directory, 30, command);
        assertThat(result.exitValue() == 0 || result.output().contains(absent)).as(result.output()).isTrue();
    }
}
