"""Execute the bundled upstream utilities using the same CLI contract as desktop."""
import contextlib
import io
import json
from pathlib import Path
import runpy
import sys
import tempfile
import threading

LOCK = threading.Lock()
SCRIPTS = dict(script="hookscore", package="title", edit="deadair",
               chapters="chapters", viral="swipe", retention="retention")


def run(root, skill, data, extra="", duration=0):
    script = Path(root, "skills", "yt-" + skill, SCRIPTS[skill] + ".py")
    with LOCK, tempfile.TemporaryDirectory(dir=root) as tmp:
        def write(name, value):
            path = Path(tmp, name)
            path.write_text(value, encoding="utf-8")
            return str(path)

        def transcript(value):
            value = value.lstrip("\ufeff")
            if value.lstrip().startswith(("[", "{")):
                parsed = json.loads(value)
                if isinstance(parsed, list):
                    value = json.dumps({"segments": parsed})
                return write("transcript.json", value)
            return write("transcript.srt", value)

        if skill == "script":
            args = ["--hook", data]
        elif skill == "package":
            args = ["--title", data, "--thumb", extra]
        elif skill == "viral":
            args = [write("videos.json", data)]
        elif skill == "retention":
            args = [write("retention.csv", data)]
            if extra.strip():
                args += ["--transcript", transcript(extra)]
            if duration > 0:
                args += ["--duration", str(duration)]
        else:
            args = [transcript(data)]
        argv, paths = sys.argv, sys.path[:]
        output = io.StringIO()
        try:
            sys.argv = [str(script)] + args + ["--json"]
            with contextlib.redirect_stdout(output):
                try:
                    runpy.run_path(str(script), run_name="__main__")
                except SystemExit as error:
                    if error.code:
                        raise ValueError(output.getvalue().strip()) from None
        finally:
            sys.argv = argv
            sys.path[:] = paths
        return output.getvalue().strip()
