import json
import sys
import unittest
from pathlib import Path

MAIN = Path(__file__).resolve().parents[2] / "main"
sys.path.insert(0, str(MAIN / "python"))
from creator_tools import run

PACK = MAIN / "assets" / "youtube-agent"


class CreatorPackTest(unittest.TestCase):
    def test_workflows_and_references(self):
        self.assertEqual(11, len(list(PACK.glob("skills/*/SKILL.md"))))
        self.assertEqual(21, len(json.loads((PACK / "skills/yt-script/hooks.json").read_text())["hooks"]))
        self.assertTrue((PACK / "templates/voice.md").is_file())

    def test_original_tools(self):
        segments = [{"start": i*5, "end": i*5+2, "text": "um" if i == 2 else f"Moment {i}"}
                    for i in range(18)]
        transcript = json.dumps({"segments": segments})
        videos = [{"channel": "one", "views": n, "title": "Test"} for n in (4, 4, 4, 20)]
        cases = [
            ("script", "I won the entire match with one arrow", "", lambda x: "properties" in x[0]),
            ("package", "One arrow", "Last shot", lambda x: "score" in x[0]),
            ("edit", transcript, "", lambda x: x["duration"] > 0),
            ("chapters", transcript, "", lambda x: x["valid"]),
            ("viral", json.dumps(videos), "", lambda x: x["outliers"][0]["multiple"] == 5),
            ("retention", "\n".join(f"{n},{100-n}" for n in range(0, 101, 5)),
             transcript, lambda x: x["points"] == 21),
        ]
        for tool, data, extra, validate in cases:
            with self.subTest(tool=tool):
                self.assertTrue(validate(json.loads(run(PACK, tool, data, extra, 90))))
        with self.assertRaises(ValueError):
            run(PACK, "chapters", "invalid")
