"""Mock nvidia-smi-exporter for Docker Desktop demo (no real GPU).

Produces Prometheus text-format metrics that mimic 4x RTX 3060 (time-slicing).
Values fluctuate slightly on each scrape to look realistic.
"""

import http.server
import random
import time

PORT = 9835

GPUS = [
    {"uuid": "GPU-aaaaaaaa-0001", "index": "0", "name": "NVIDIA GeForce RTX 3060"},
    {"uuid": "GPU-aaaaaaaa-0002", "index": "1", "name": "NVIDIA GeForce RTX 3060"},
    {"uuid": "GPU-aaaaaaaa-0003", "index": "2", "name": "NVIDIA GeForce RTX 3060"},
    {"uuid": "GPU-aaaaaaaa-0004", "index": "3", "name": "NVIDIA GeForce RTX 3060"},
]

DRIVER_VERSION = "535.183.01"
CUDA_VERSION = "12.2"
MEMORY_TOTAL = 12884901888  # 12 GB


def _metrics():
    lines = []

    # gpu_operator_gpu_nodes_total
    lines.append("# HELP gpu_operator_gpu_nodes_total Number of GPU nodes")
    lines.append("# TYPE gpu_operator_gpu_nodes_total gauge")
    lines.append("gpu_operator_gpu_nodes_total 1")

    # nvidia_smi_gpu_info
    lines.append("# HELP nvidia_smi_gpu_info GPU device info")
    lines.append("# TYPE nvidia_smi_gpu_info gauge")
    for g in GPUS:
        lines.append(
            f'nvidia_smi_gpu_info{{uuid="{g["uuid"]}",index="{g["index"]}",'
            f'name="{g["name"]}",driver_version="{DRIVER_VERSION}",'
            f'cuda_version="{CUDA_VERSION}"}} 1'
        )

    # nvidia_smi_utilization_gpu_ratio
    lines.append("# HELP nvidia_smi_utilization_gpu_ratio GPU utilization (0-1)")
    lines.append("# TYPE nvidia_smi_utilization_gpu_ratio gauge")
    for g in GPUS:
        val = round(random.uniform(0.40, 0.55), 4)
        lines.append(
            f'nvidia_smi_utilization_gpu_ratio{{uuid="{g["uuid"]}",index="{g["index"]}",'
            f'name="{g["name"]}"}} {val}'
        )

    # nvidia_smi_memory_used_bytes
    lines.append("# HELP nvidia_smi_memory_used_bytes GPU memory used in bytes")
    lines.append("# TYPE nvidia_smi_memory_used_bytes gauge")
    for g in GPUS:
        used = random.randint(2_800_000_000, 3_500_000_000)
        lines.append(
            f'nvidia_smi_memory_used_bytes{{uuid="{g["uuid"]}",index="{g["index"]}",'
            f'name="{g["name"]}"}} {used}'
        )

    # nvidia_smi_memory_total_bytes
    lines.append("# HELP nvidia_smi_memory_total_bytes GPU memory total in bytes")
    lines.append("# TYPE nvidia_smi_memory_total_bytes gauge")
    for g in GPUS:
        lines.append(
            f'nvidia_smi_memory_total_bytes{{uuid="{g["uuid"]}",index="{g["index"]}",'
            f'name="{g["name"]}"}} {MEMORY_TOTAL}'
        )

    # nvidia_smi_temperature_gpu
    lines.append("# HELP nvidia_smi_temperature_gpu GPU temperature in Celsius")
    lines.append("# TYPE nvidia_smi_temperature_gpu gauge")
    for g in GPUS:
        temp = random.randint(44, 50)
        lines.append(
            f'nvidia_smi_temperature_gpu{{uuid="{g["uuid"]}",index="{g["index"]}",'
            f'name="{g["name"]}"}} {temp}'
        )

    # nvidia_smi_power_draw_watts
    lines.append("# HELP nvidia_smi_power_draw_watts GPU power draw in watts")
    lines.append("# TYPE nvidia_smi_power_draw_watts gauge")
    for g in GPUS:
        power = round(random.uniform(18.0, 25.0), 1)
        lines.append(
            f'nvidia_smi_power_draw_watts{{uuid="{g["uuid"]}",index="{g["index"]}",'
            f'name="{g["name"]}"}} {power}'
        )

    # nvidia_smi_fan_speed_ratio
    lines.append("# HELP nvidia_smi_fan_speed_ratio GPU fan speed (0-1)")
    lines.append("# TYPE nvidia_smi_fan_speed_ratio gauge")
    for g in GPUS:
        lines.append(
            f'nvidia_smi_fan_speed_ratio{{uuid="{g["uuid"]}",index="{g["index"]}",'
            f'name="{g["name"]}"}} 0.0'
        )

    return "\n".join(lines) + "\n"


class MetricsHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/metrics":
            body = _metrics().encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"mock nvidia-smi-exporter\n")

    def log_message(self, fmt, *args):
        pass  # suppress request logs


if __name__ == "__main__":
    print(f"Mock nvidia-smi-exporter listening on :{PORT}")
    server = http.server.HTTPServer(("", PORT), MetricsHandler)
    server.serve_forever()
