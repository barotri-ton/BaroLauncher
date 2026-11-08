import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("summary", Path(__file__).with_name("summarize.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

MEMINFO = """Applications Memory Usage (in Kilobytes):
                   Pss  Private  Private  SwapPss     Heap     Heap     Heap
                 Total    Dirty    Clean    Dirty     Size    Alloc     Free
                ------   ------   ------   ------   ------   ------   ------
  Native Heap     5000     4900        0        0    20000    12000     8000
  Dalvik Heap      8000     7800        0        0    30000    15000    15000
     Graphics      2000     2000        0        0
        TOTAL     22000    21000        0        0
"""

class ParserFixtures(unittest.TestCase):
    def test_meminfo_distinguishes_pss_and_heap_alloc(self):
        m = module.parse_mem(MEMINFO)
        self.assertEqual(m["total_pss_kb"], 22000)
        self.assertEqual(m["dalvik_pss_kb"], 8000)
        self.assertEqual(m["native_pss_kb"], 5000)
        self.assertEqual(m["graphics_pss_kb"], 2000)
        self.assertEqual(m["java_heap_alloc_kb"], 15000)
        self.assertEqual(m["native_heap_alloc_kb"], 12000)
        self.assertNotEqual(m["native_pss_kb"], m["native_heap_alloc_kb"])

    def test_missing_metrics_not_fabricated(self):
        self.assertIsNone(module.parse_mem("TOTAL PSS: 1234")["java_heap_alloc_kb"])

    def test_startup(self):
        self.assertEqual(module.parse_start("Status: ok\nTotalTime: 345\nWaitTime: 356"), 345)
        self.assertIsNone(module.parse_start("Warning: Activity not started"))

    def test_frames_deduplicated(self):
        row = ["0", "1000000000"] + ["0"] * 11 + ["1016000000"] + ["0"] * 2
        raw = "---PROFILEDATA---\n" + ",".join(row) + "\n" + ",".join(row) + "\n---PROFILEDATA---\n"
        frames = module.parse_frames(raw)
        self.assertEqual(len(frames), 1)
        self.assertEqual(next(iter(frames.values())), 16)

    def test_missing_metric_value_is_none(self):
        m = module.parse_mem(MEMINFO.replace("  Native Heap     5000     4900        0        0    20000    12000     8000", ""))
        self.assertIsNone(m["native_heap_alloc_kb"])
        self.assertIsNone(m["native_pss_kb"])

    def test_missing_startup_is_not_fabricated(self):
        self.assertIsNone(module.parse_start("Status: ok\nWarning: not launched"))

    def test_sha256_validation_rejects_short_hash(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "manifest.txt").write_text(
                "kind=emulator\napi=28\nruns=1\nnetwork_state=on\n"
                "feature_toggles=default\napk_sha256=abc\napk_provenance=local\ninstalled_app_count=1\n"
            )
            with self.assertRaisesRegex(ValueError, "Invalid APK SHA-256"):
                module.main(root)

    def test_missing_sample_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "manifest.txt").write_text("kind=emulator\napi=28\nruns=1\nnetwork_state=off\nfeature_toggles=default\napk_sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\napk_provenance=local\ninstalled_app_count=1\n")
            with self.assertRaisesRegex(ValueError, "Missing memory sample"):
                module.main(root)

if __name__ == "__main__":
    unittest.main()
