"""Generate deterministic QA tones and subtitles; never bundled with the app."""
import math
import struct
import wave
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "test-assets" / "RJ10"
for folder, name, seconds in [("A", "1", 12), ("A", "2", 120), ("A", "10", 15), ("B", "1", 30), ("Bonus/A", "1", 20)]:
    directory = root / folder
    directory.mkdir(parents=True, exist_ok=True)
    with wave.open(str(directory / (name + ".wav")), "wb") as audio:
        audio.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
        for second in range(seconds):
            audio.writeframes(b"".join(struct.pack("<h", int(700 * math.sin(2 * math.pi * 220 * (second + i / 16000)))) for i in range(16000)))
(root / "A" / "1.txt").write_text("【00:01.000 这是第一段测试音频】\n[00:04.000] 字幕会随播放同步\n00:08.000 点击文字可以跳转", encoding="utf-8")
(root / "A" / "2.vtt").write_text("WEBVTT\n\n00:00.000 --> 00:30.000\n第二段：后台播放与定时测试\n\n00:30.000 --> 00:55.000\n保持播放\n\n00:55.000 --> 02:00.000\n睡眠定时会在后台暂停\n", encoding="utf-8")
(root / "B" / "1.lrc").write_text("[00:01.00]这是 B 文件夹\n[00:05.00]同名音频不会混在一起\n[00:10.00]放松一下", encoding="utf-8")
(root / "Bonus" / "A" / "1.txt").write_text("这是普通文稿，没有时间戳。\n可在应用中阅读，不会自动生成时间轴。", encoding="utf-8")
print(root)
