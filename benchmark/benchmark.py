"""Benchmark Harness: runs a Benchmark Run (see CONTEXT.md) and produces a Benchmark Report."""

import argparse
import io
import json
import sys
import tarfile
import threading
import time
from datetime import datetime
from pathlib import Path

import docker
import docker.errors
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

# Resource Profile (ADR-0008): hard CPU/RAM cap per process, enforced by Docker.
# Script config, not a CLI flag -- edit here to test a different envelope.
SERVER_CPUS = 2.0
SERVER_MEMORY_MB = 256
CLIENT_CPUS = 4.0
CLIENT_MEMORY_MB = 1024

SERVER_IMAGE = "registration-server:latest"
CLIENT_IMAGE = "registration-client:latest"
NETWORK_NAME = "registration-benchmark-net"
SERVER_CONTAINER_NAME = "registration-benchmark-server"
CLIENT_CONTAINER_NAME = "registration-benchmark-client"
SERVER_PORT = 9000
SERVER_HTTP_PORT = 8080
IDLE_WAIT_SECONDS = 5
COOLDOWN_WAIT_SECONDS = 5

# Written by BenchmarkReport.java into the Client container's working directory
# (client/Dockerfile's WORKDIR) once Benchmark Mode self-terminates.
CLIENT_REPORT_PATH = "/app/benchmark-report.json"

# Leaves headroom below the container's hard --memory cap so the JVM hits its own
# heap ceiling (a normal OutOfMemoryError) rather than getting OOM-killed by the kernel.
HEAP_FRACTION = 0.8


def parse_args():
    parser = argparse.ArgumentParser(description="Registration Platform Benchmark Harness")
    parser.add_argument("--clients", type=int, default=10_000, help="Simulated Client count (Load Profile)")
    parser.add_argument("--rate", type=float, default=1_000.0, help="Register rate per second (Load Profile)")
    parser.add_argument("--duration", type=int, default=60, help="Benchmark Duration in seconds (Load Profile)")
    parser.add_argument("--renew-min", type=int, default=50, help="Benchmark Duration in seconds (Load Profile)")
    parser.add_argument("--renew-max", type=int, default=70, help="Benchmark Duration in seconds (Load Profile)")

    return parser.parse_args()


def java_opts(cpus: float, memory_mb: int) -> str:
    heap_mb = int(memory_mb * HEAP_FRACTION)
    return f"-Xmx{heap_mb}m -XX:ActiveProcessorCount={int(cpus)}"


def parse_container_stats(raw: dict) -> tuple[float, float, float]:
    cpu_stats = raw.get("cpu_stats", {})
    precpu_stats = raw.get("precpu_stats", {})
    cpu_usage = cpu_stats.get("cpu_usage", {})
    precpu_usage = precpu_stats.get("cpu_usage", {})

    cpu_delta = cpu_usage.get("total_usage", 0) - precpu_usage.get("total_usage", 0)
    system_delta = cpu_stats.get("system_cpu_usage", 0) - precpu_stats.get("system_cpu_usage", 0)
    online_cpus = cpu_stats.get("online_cpus") or len(cpu_usage.get("percpu_usage") or [1]) or 1

    cpu_percent = 0.0
    if system_delta > 0 and cpu_delta > 0:
        cpu_percent = (cpu_delta / system_delta) * online_cpus * 100.0

    mem_stats = raw.get("memory_stats", {})
    mem_usage = mem_stats.get("usage", 0)
    inner = mem_stats.get("stats", {})
    # Page cache counts toward the cgroup's "usage" but isn't memory pressure; strip it
    # so the chart reflects working-set RAM, not incidental filesystem cache.
    cache = inner.get("inactive_file", inner.get("cache", 0))
    mem_mb = max(mem_usage - cache, 0) / (1024 * 1024)
    mem_limit_mb = mem_stats.get("limit", 0) / (1024 * 1024)

    return cpu_percent, mem_mb, mem_limit_mb


class StatsSampler:
    """Samples one container's docker stats stream against a shared run timeline."""

    def __init__(self, container, run_start: float):
        self._container = container
        self._run_start = run_start
        self._stop = threading.Event()
        self.samples: list[tuple[float, float, float, float]] = []
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self._thread.start()

    def stop(self):
        self._stop.set()

    def _run(self):
        first = True
        try:
            for raw in self._container.stats(stream=True, decode=True):
                if self._stop.is_set():
                    return
                if first:
                    # The stream's first record has an empty precpu_stats (no prior
                    # reading yet), which would compute as a spurious 0% cpu sample.
                    first = False
                    continue
                cpu_percent, mem_mb, mem_limit_mb = parse_container_stats(raw)
                elapsed = time.monotonic() - self._run_start
                self.samples.append((elapsed, cpu_percent, mem_mb, mem_limit_mb))
        except docker.errors.NotFound:
            return


def build_docker_client() -> docker.DockerClient:
    try:
        return docker.from_env()
    except docker.errors.DockerException as e:
        sys.exit(f"Could not connect to Docker: {e}\nIs Docker Desktop running?")


def ensure_images_exist(client: docker.DockerClient):
    missing = []
    for image, dockerfile in ((SERVER_IMAGE, "server/Dockerfile"), (CLIENT_IMAGE, "client/Dockerfile")):
        try:
            client.images.get(image)
        except docker.errors.ImageNotFound:
            missing.append((image, dockerfile))

    if missing:
        lines = ["Missing Docker image(s). Build them first (from the repo root):", ""]
        for image, dockerfile in missing:
            module = dockerfile.split("/")[0]
            lines.append(f"  mvn -pl {module} -am package -DskipTests")
            lines.append(f"  docker build -t {image} -f {dockerfile} {module}/")
            lines.append("")
        sys.exit("\n".join(lines))


def fetch_client_report(container) -> dict | None:
    """Pulls the Client's benchmark-report.json out of the container before it's removed
    (docker.errors.NotFound means the Client never reached Benchmark Mode's natural
    self-termination - e.g. it crashed - so there's nothing to fetch)."""
    try:
        chunks, _stat = container.get_archive(CLIENT_REPORT_PATH)
        with tarfile.open(fileobj=io.BytesIO(b"".join(chunks))) as tar:
            member = tar.getmember("benchmark-report.json")
            with tar.extractfile(member) as f:
                return json.load(f)
    except docker.errors.NotFound:
        print(f"Warning: Client did not produce {CLIENT_REPORT_PATH} (did it exit abnormally?)")
        return None


def _print_client_diagnostics(container):
    """The container is about to be removed either way - surface whatever might explain a
    missing benchmark-report.json (OOM kill, uncaught exception, ...) while it's still around."""
    try:
        state = container.attrs.get("State", {})
        print(f"    Client exit code: {state.get('ExitCode')}, OOMKilled: {state.get('OOMKilled')}")
        tail = container.logs(tail=40).decode(errors="replace")
        print("    Last 40 lines of Client logs:")
        for line in tail.splitlines():
            print(f"      {line}")
    except docker.errors.NotFound:
        pass


def cleanup(client: docker.DockerClient):
    for name in (SERVER_CONTAINER_NAME, CLIENT_CONTAINER_NAME):
        try:
            container = client.containers.get(name)
            container.remove(force=True)
        except docker.errors.NotFound:
            pass
    try:
        client.networks.get(NETWORK_NAME).remove()
    except docker.errors.NotFound:
        pass


def run_benchmark(args) -> Path:
    client = build_docker_client()
    ensure_images_exist(client)
    cleanup(client)

    network = client.networks.create(NETWORK_NAME, driver="bridge")
    run_start = time.monotonic()

    server_container = None
    client_container = None
    try:
        print("--> Starting Server...")
        server_container = client.containers.run(
            SERVER_IMAGE,
            name=SERVER_CONTAINER_NAME,
            detach=True,
            network=NETWORK_NAME,
            nano_cpus=int(SERVER_CPUS * 1_000_000_000),
            mem_limit=f"{SERVER_MEMORY_MB}m",
            environment={"JAVA_OPTS": java_opts(SERVER_CPUS, SERVER_MEMORY_MB)},
            ports={
                f"{SERVER_PORT}/tcp": SERVER_PORT,
                f"{SERVER_HTTP_PORT}/tcp": SERVER_HTTP_PORT
            },
        )

        print(f"--> Idle phase ({IDLE_WAIT_SECONDS}s)...")
        idle_sampler = StatsSampler(server_container, run_start)
        idle_sampler.start()
        time.sleep(IDLE_WAIT_SECONDS)
        idle_sampler.stop()
        phase_boundary = time.monotonic() - run_start

        print(f"--> Starting Client (clients={args.clients}, rate={args.rate}/s, duration={args.duration}s)...")
        client_container = client.containers.run(
            CLIENT_IMAGE,
            name=CLIENT_CONTAINER_NAME,
            detach=True,
            network=NETWORK_NAME,
            nano_cpus=int(CLIENT_CPUS * 1_000_000_000),
            mem_limit=f"{CLIENT_MEMORY_MB}m",
            environment={"JAVA_OPTS": java_opts(CLIENT_CPUS, CLIENT_MEMORY_MB)},
            command=[
                "--client.mode=benchmark",
                f"--client.server-host={SERVER_CONTAINER_NAME}",
                f"--client.server-port={SERVER_PORT}",
                f"--client.simulated-clients={args.clients}",
                f"--client.register-rate-per-second={args.rate}",
                f"--client.benchmark-duration-seconds={args.duration}",
            ],
        )

        print("--> Active phase (until Client self-terminates)...")
        active_server_sampler = StatsSampler(server_container, run_start)
        active_client_sampler = StatsSampler(client_container, run_start)
        active_server_sampler.start()
        active_client_sampler.start()

        client_container.wait()

        active_server_sampler.stop()
        active_client_sampler.stop()

        # Before removal in the `finally` block below - the container's filesystem
        # (and benchmark-report.json with it) disappears once that runs.
        client_report = fetch_client_report(client_container)
        if client_report is None:
            _print_client_diagnostics(client_container)

        print(f"--> Client finished. Cooldown ({COOLDOWN_WAIT_SECONDS}s)...")
        time.sleep(COOLDOWN_WAIT_SECONDS)

        return generate_report(
            args,
            idle_samples=idle_sampler.samples,
            active_server_samples=active_server_sampler.samples,
            active_client_samples=active_client_sampler.samples,
            phase_boundary=phase_boundary,
            client_report=client_report,
        )
    finally:
        print("--> Shutting down...")
        if server_container is not None:
            server_container.remove(force=True)
        if client_container is not None:
            client_container.remove(force=True)
        network.remove()


def _plot(ax_cpu, ax_mem, samples, label, phase_boundary=None):
    if not samples:
        return
    elapsed = [s[0] for s in samples]
    cpu = [s[1] for s in samples]
    mem = [s[2] for s in samples]
    mem_limit = samples[-1][3]

    ax_cpu.plot(elapsed, cpu, label=label)
    ax_mem.plot(elapsed, mem, label=label)
    if mem_limit:
        ax_mem.axhline(mem_limit, color="red", linestyle=":", linewidth=1, label=f"{label} limit")
    if phase_boundary is not None:
        for ax in (ax_cpu, ax_mem):
            ax.axvline(phase_boundary, color="gray", linestyle="--", linewidth=1)
            ax.text(phase_boundary, ax.get_ylim()[1], " Active ->", va="top", fontsize=8, color="gray")


def _save_chart(path: Path, title: str, samples, phase_boundary=None):
    fig, (ax_cpu, ax_mem) = plt.subplots(2, 1, figsize=(10, 6), sharex=True)
    fig.suptitle(title)

    _plot(ax_cpu, ax_mem, samples, title, phase_boundary)

    ax_cpu.set_ylabel("CPU %")
    ax_cpu.legend(loc="upper right", fontsize=8)
    ax_mem.set_ylabel("RAM (MB)")
    ax_mem.set_xlabel("Elapsed (s)")
    ax_mem.legend(loc="upper right", fontsize=8)

    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def _averages(samples):
    if not samples:
        return 0.0, 0.0
    cpu = sum(s[1] for s in samples) / len(samples)
    mem = sum(s[2] for s in samples) / len(samples)
    return cpu, mem


def _client_report_lines(client_report: dict | None) -> list[str]:
    """Renders the Client's own benchmark-report.json (BenchmarkReport.java) into the same
    fixed-width style as the rest of the summary. None means the Client never reached
    Benchmark Mode's natural self-termination (see fetch_client_report)."""
    if client_report is None:
        return ["-" * 45, "Client Report    : not available (Client exited abnormally)"]

    lines = [
        "-" * 45,
        f"Total Requests   : {client_report['totalRequests']}",
        f"Total Timeouts   : {client_report['totalTimeouts']}",
        f"Total Retries    : {client_report['totalRetries']}",
    ]
    for label, key in (("REGISTER", "register"), ("RENEW", "renew")):
        op = client_report[key]
        lines.append(
            f"{label:<8} : success={op['successes']:<6} failure={op['failures']:<6} "
            f"avg={op['averageResponseTimeMillis']:>6.2f}ms "
            f"min={op['minResponseTimeMillis']:>5}ms max={op['maxResponseTimeMillis']:>5}ms"
        )
    return lines


def generate_report(
    args, idle_samples, active_server_samples, active_client_samples, phase_boundary, client_report=None
) -> Path:
    report_dir = Path(__file__).parent / "reports" / datetime.now().strftime("%Y%m%d-%H%M%S")
    report_dir.mkdir(parents=True, exist_ok=True)

    server_samples = idle_samples + active_server_samples
    _save_chart(report_dir / "server.png", "Server", server_samples, phase_boundary)
    _save_chart(report_dir / "client.png", "Client", active_client_samples)

    if client_report is not None:
        (report_dir / "client-benchmark-report.json").write_text(json.dumps(client_report, indent=2) + "\n")

    idle_cpu, idle_mem = _averages(idle_samples)
    active_server_cpu, active_server_mem = _averages(active_server_samples)
    active_client_cpu, active_client_mem = _averages(active_client_samples)

    summary = "\n".join(
        [
            "=" * 45,
            " BENCHMARK REPORT ".center(45, "="),
            "=" * 45,
            f"Load Profile     : {args.clients} clients | {args.rate}/s | {args.duration}s",
            f"Resource Server Profile : {SERVER_CPUS} CPU / {SERVER_MEMORY_MB}MB",
            f"Resource Server Profile : {CLIENT_CPUS} CPU / {SERVER_MEMORY_MB}MB",
            "-" * 45,
            f"Server Idle      : CPU {idle_cpu:>5.1f}% | RAM {idle_mem:>7.1f} MB",
            f"Server Active    : CPU {active_server_cpu:>5.1f}% | RAM {active_server_mem:>7.1f} MB",
            f"Client Active    : CPU {active_client_cpu:>5.1f}% | RAM {active_client_mem:>7.1f} MB",
            *_client_report_lines(client_report),
            "=" * 45,
        ]
    )
    print("\n" + summary)
    (report_dir / "summary.txt").write_text(summary + "\n")

    print(f"\nReport written to {report_dir}")
    return report_dir


if __name__ == "__main__":
    run_benchmark(parse_args())
