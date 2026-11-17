"""ODK's own words for each key of a project's configuration, as OntoBoard's Project
configuration shows them beside the keys (odk/OdkSchema).

ODK v1.6.1's project-schema.json carries no descriptions; ODK's own documentation generator
(odk/schema_documentation.py) takes them from odk.py's source - the docstring under each field
of its dataclasses - and so does this, in ODK's image, from the odk.py the image runs. Each is
flattened to the dotted key a configuration file has: a field of a nested object under that
object's key, the fields of a list's items under the list's key (import_group.products.id), a
class's fields with those of the classes it extends. A field ODK gives no docstring is listed
with an empty description: it is a key of ODK's all the same.
"""

import hashlib

# Run with python3 -I, odk.py's path its argument. Prints "key<TAB>description" lines, sorted.
SCRIPT = r'''
import ast, sys
source = open(sys.argv[1], encoding="utf-8").read()
tree = ast.parse(source)
classes = {}
for node in tree.body:
    if not isinstance(node, ast.ClassDef):
        continue
    bases = [b.id for b in node.bases if isinstance(b, ast.Name)]
    fields = []
    body = node.body
    for at, statement in enumerate(body):
        if isinstance(statement, ast.AnnAssign) and isinstance(statement.target, ast.Name):
            doc = None
            if at + 1 < len(body):
                following = body[at + 1]
                if (isinstance(following, ast.Expr) and isinstance(following.value, ast.Constant)
                        and isinstance(following.value.value, str)):
                    doc = " ".join(following.value.value.split())
            fields.append((statement.target.id, statement.annotation, doc))
    classes[node.name] = (bases, fields)

def all_fields(name):
    bases, own = classes[name]
    inherited = []
    for base in bases:
        if base in classes:
            inherited.extend(all_fields(base))
    mine = set(field[0] for field in own)
    return [field for field in inherited if field[0] not in mine] + own

def classes_in(annotation):
    found = []
    for part in ast.walk(annotation):
        if isinstance(part, ast.Name) and part.id in classes:
            found.append(part.id)
        elif isinstance(part, ast.Constant) and isinstance(part.value, str) and part.value in classes:
            found.append(part.value)
    return found

rows = {}

def walk(name, prefix, depth):
    if depth > 8:
        return
    for field, annotation, doc in all_fields(name):
        key = prefix + field
        # A field without a docstring is ODK's all the same: listed, its description empty.
        rows[key] = doc or ""
        for inner in classes_in(annotation):
            walk(inner, key + ".", depth + 1)

walk("OntologyProject", "", 0)
# UTF-8 and LF whatever the platform: a pipe on Windows is written in its code page, CRLF.
for key in sorted(rows):
    sys.stdout.buffer.write((key + "\t" + rows[key] + "\n").encode("utf-8"))
'''


def run(tool, image, odkimage, path):
    """From the image's /tools/odk.py: writes the descriptions to `path`; returns (how many,
    odk.py's SHA-256)."""
    _, out, _ = odkimage.in_image(tool, image, ["python3", "-I", "-c", SCRIPT, "/tools/odk.py"])
    _, source, _ = odkimage.in_image(tool, image, ["cat", "/tools/odk.py"])
    return written(out, source, odkimage, path)


def run_on_source(odk_py, odkimage, path):
    """The same from odk.py of ODK's source at the pinned tag, with this machine's Python - for
    when no container runs. The image's run gives the same bytes; its odk.py is this file."""
    import subprocess
    import sys
    out = subprocess.run([sys.executable, "-I", "-c", SCRIPT, odk_py], check=True,
                         capture_output=True).stdout
    with open(odk_py, "rb") as source:
        return written(out, source.read(), odkimage, path)


def written(out, source, odkimage, path):
    text = out.decode("utf-8").replace("\r\n", "\n")
    odkimage.write_text(path, text)
    # The SHA-256 of odk.py with LF line ends: a checkout on Windows has CRLF, the image LF.
    lf = source.replace(b"\r\n", b"\n")
    return len([line for line in text.split("\n") if line]), hashlib.sha256(lf).hexdigest()
