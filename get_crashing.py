import sys
import glob
import os

res_dir = sys.argv[1]

def has_exception_happened(lines):
    index = len(lines) - 1
    while index >= 0 and lines[index].find("Analyzed klass") < 0:
        index -= 1
    index -= 1
    if index < 0:
        return True
    if lines[index].startswith("\tat"):
        return True
    return False

for f in glob.glob(res_dir + "/*"):
    filename = os.path.join(f, "temp", "kex.log")
    if len(glob.glob(filename)) == 0:
        continue
    with open(os.path.join(f, "temp", "kex.log"), 'rb') as infile:
        infile.seek(-10 * 1024, os.SEEK_END)
        lines = [x.decode('utf-8', errors='replace') for x in infile.readlines()]
        if has_exception_happened(lines):
            print(f)
