#!/usr/bin/env python3
from __future__ import annotations

import argparse
import pathlib
import zipfile


def build_submission_zip(project_root: pathlib.Path, zip_name: str, outer_folder: str) -> pathlib.Path:
    src_dir = project_root / "src"
    pom_file = project_root / "pom.xml"

    if not src_dir.is_dir():
        raise FileNotFoundError(f"Missing source directory: {src_dir}")
    if not pom_file.is_file():
        raise FileNotFoundError(f"Missing pom.xml: {pom_file}")

    output_zip = project_root / zip_name
    if output_zip.exists():
        output_zip.unlink()

    with zipfile.ZipFile(output_zip, mode="w", compression=zipfile.ZIP_DEFLATED) as zf:
        # Include pom.xml
        zf.write(pom_file, arcname=f"{outer_folder}/pom.xml")

        # Include src recursively
        for path in src_dir.rglob("*"):
            if path.is_file():
                relative = path.relative_to(project_root)
                # Skip hidden/internal artifacts such as .antlr directories.
                if any(part.startswith(".") for part in relative.parts):
                    continue
                arcname = pathlib.Path(outer_folder) / relative
                zf.write(path, arcname=str(arcname).replace("\\", "/"))

    return output_zip


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create submission.zip with structure: outer_folder/src and outer_folder/pom.xml"
    )
    parser.add_argument(
        "--zip-name",
        default="submission.zip",
        help="Output zip file name (default: submission.zip)",
    )
    parser.add_argument(
        "--outer-folder",
        default="outer_folder",
        help="Top-level folder name inside zip (default: outer_folder)",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    project_root = pathlib.Path(__file__).resolve().parent
    output_zip = build_submission_zip(project_root, args.zip_name, args.outer_folder)
    print(f"Created: {output_zip}")


if __name__ == "__main__":
    main()
