"""Requires a real, checksum-verified ONNX model. No generated vectors stand in for inference."""

import os
from pathlib import Path
import unittest
import numpy as np
from app import Encoder


class SemanticModelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.encoder = Encoder(Path(os.getenv("MODEL_DIRECTORY", "/model")))

    def test_russian_meaning_and_long_documents(self):
        a = np.asarray(
            self.encoder.encode(
                "Я готовился к марафону. Бегал длинные дистанции и контролировал пульс."
            )
        )
        related = np.asarray(
            self.encoder.encode(
                "Подготовка к забегу на сорок два километра: беговые тренировки и работа над выносливостью."
            )
        )
        unrelated = np.asarray(
            self.encoder.encode(
                "Я испёк яблочный пирог в духовке. Рецепт включает муку, яйца и сахар."
            )
        )
        self.assertEqual(a.shape, (384,))
        self.assertAlmostEqual(float(np.linalg.norm(a)), 1, places=4)
        self.assertGreater(float(a @ related), float(a @ unrelated))
        long = np.asarray(
            self.encoder.encode(
                ("История о беге и подготовке к марафону. " * 520)[:20000]
            )
        )
        self.assertAlmostEqual(float(np.linalg.norm(long)), 1, places=4)


if __name__ == "__main__":
    unittest.main()
