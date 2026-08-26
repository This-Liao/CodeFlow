"""Generate the deterministic README terminal animation."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT = 1200, 680
ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs" / "assets" / "codeflow-demo.gif"


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    candidates = [
        Path("C:/Windows/Fonts/consolab.ttf" if bold else "C:/Windows/Fonts/consola.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default(size=size)


MONO = font(23)
MONO_BOLD = font(23, True)
TITLE = font(19, True)

SCENES: list[list[tuple[str, str]]] = [
    [("$ ", "cyan"), ("java -jar codeflow.jar --durable -p ", "white"),
     ('"Analyze repo; delegate static analysis"', "yellow")],
    [("Durable task   ", "muted"), ("cf-8e91d7b2", "green"),
     ("Lifecycle      ", "purple"), ("CREATED → PLANNING", "white")],
    [("ContextPolicy ", "blue"), ("stage=PLANNING · schemas=18/42", "white"),
     ("ToolSearch     ", "blue"), ("loaded A2ADelegate, DurableTask", "white")],
    [("Agent Card     ", "pink"), ("CodeFlow Python Static Analysis Agent", "white"),
     ("Protocol       ", "pink"), ("A2A 1.0 · HTTP+JSON", "white")],
    [("Remote task    ", "pink"), ("TASK_STATE_SUBMITTED → TASK_STATE_WORKING", "white"),
     ("Durable task   ", "green"), ("PLANNING → EXECUTING", "white")],
    [("Artifact       ", "yellow"), ("static-analysis-report  text/markdown", "white"),
     ("Artifact       ", "yellow"), ("findings.json            application/json", "white")],
    [("Verification   ", "purple"), ("EXECUTING → VERIFYING → COMPLETED", "white"),
     ("Checkpoint     ", "green"), ("snapshot + event log persisted", "white")],
    [("Trace Eval     ", "blue"), ("success=100%  tool_errors=0%", "white"),
     ("Metrics        ", "blue"), ("tokens=2,418  p50=1.2s  p95=2.8s", "white"),
     ("Regression gate", "green"), ("PASS", "green")],
]

COLORS = {
    "bg": "#08111f", "panel": "#0e1a2d", "border": "#2a4268", "white": "#edf4ff",
    "muted": "#8296b8", "cyan": "#4cc9f0", "blue": "#65a5ff", "green": "#54d6a0",
    "purple": "#a991ff", "pink": "#ff79b8", "yellow": "#ffd166",
}


def draw_frame(scene_index: int) -> Image.Image:
    image = Image.new("RGB", (WIDTH, HEIGHT), COLORS["bg"])
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((38, 30, WIDTH - 38, HEIGHT - 30), 22, fill=COLORS["panel"], outline=COLORS["border"], width=3)
    draw.rounded_rectangle((38, 30, WIDTH - 38, 88), 22, fill="#14233a")
    draw.rectangle((38, 64, WIDTH - 38, 88), fill="#14233a")
    for x, color in [(70, "#ff6577"), (104, "#ffd166"), (138, "#54d6a0")]:
        draw.ellipse((x - 8, 51 - 8, x + 8, 51 + 8), fill=color)
    draw.text((WIDTH // 2, 51), "CodeFlow · durable A2A run", font=TITLE, fill="#b9c8e5", anchor="mm")

    y = 122
    line_height = 42
    lines: list[tuple[tuple[str, str], tuple[str, str]]] = []
    for group in SCENES[1 : scene_index + 1]:
        for pair in range(0, len(group), 2):
            lines.append((group[pair], group[pair + 1]))
    if len(lines) <= 10:
        x = 72
        for value, color in SCENES[0]:
            draw.text((x, y), value, font=MONO_BOLD if color == "cyan" else MONO, fill=COLORS[color])
            x += draw.textlength(value, font=MONO_BOLD if color == "cyan" else MONO)
        y += line_height + 12
    else:
        draw.text((72, y), "... earlier task events ...", font=MONO, fill=COLORS["muted"])
        y += line_height + 4
        lines = lines[-10:]
    for (label, label_color), (value, value_color) in lines:
        draw.text((72, y), label, font=MONO_BOLD, fill=COLORS[label_color])
        draw.text((285, y), value, font=MONO, fill=COLORS[value_color])
        y += line_height

    state = ["PLANNING", "PLANNING", "PLANNING", "EXECUTING", "EXECUTING", "VERIFYING", "COMPLETED", "EVAL PASS"][scene_index]
    color = COLORS["green"] if scene_index >= 6 else COLORS["purple"] if scene_index < 4 else COLORS["pink"]
    draw.rounded_rectangle((WIDTH - 260, HEIGHT - 88, WIDTH - 70, HEIGHT - 52), 18, outline=color, width=2)
    draw.text((WIDTH - 165, HEIGHT - 70), state, font=TITLE, fill=color, anchor="mm")
    draw.text((72, HEIGHT - 70), "Java Host  <->  A2A 1.0  <->  Python LangGraph", font=TITLE, fill=COLORS["muted"], anchor="lm")
    return image


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    frames = [draw_frame(index) for index in range(len(SCENES))]
    frames[0].save(
        OUTPUT,
        save_all=True,
        append_images=frames[1:],
        duration=[1300, 900, 1100, 1200, 1100, 1200, 1100, 2200],
        loop=0,
        optimize=True,
    )
    print(OUTPUT)


if __name__ == "__main__":
    main()
