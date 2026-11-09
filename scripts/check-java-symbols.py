"""Cross-file sanity check for the Android sources. Run: python3 scripts/check-java-symbols.py

Two failure classes that the compiler catches and that are easy to introduce at speed:
  1. calling a method on a local class that does not exist, or with the wrong number of arguments
  2. calling a method from another class that is declared private in its own class

This is not a compiler; it is a fast tripwire before pushing to CI.
"""
import re, os

HERE = os.path.dirname(os.path.abspath(__file__))
root = os.path.join(HERE, '..', 'android', 'app', 'src', 'main', 'java', 'com', 'myraa', 'mobile')
files = {}
for name in os.listdir(root):
    if name.endswith('.java'):
        files[name[:-5]] = open(os.path.join(root, name), encoding='utf-8').read()


def strip(text):
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)
    text = re.sub(r'(?<!\\)//[^\n]*', ' ', text)
    text = re.sub(r'"(\\.|[^"\\\n])*"', '""', text)
    text = re.sub(r"'(\\.|[^'\\\n])'", "''", text)
    return text


def body_flat(text):
    """Class body with every member body collapsed to '{}' (keeps signatures readable)."""
    brace = text.find('{')
    if brace < 0:
        return ''
    out, depth, i = [], 0, brace
    while i < len(text):
        c = text[i]
        if c == '{':
            if depth >= 1:  # inside the class already: skip this whole block
                inner = 1
                i += 1
                while i < len(text) and inner:
                    if text[i] == '{':
                        inner += 1
                    elif text[i] == '}':
                        inner -= 1
                    i += 1
                out.append('{ }')
                continue
            depth = 1
            out.append('{')
            i += 1
            continue
        if c == '}':
            depth = 0
            out.append(c)
            i += 1
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def split_params(raw):
    params, depth, cur = [], 0, ''
    for ch in raw:
        if ch in '<([':
            depth += 1
        elif ch in '>)]':
            depth -= 1
        if ch == ',' and depth == 0:
            params.append(cur)
            cur = ''
        else:
            cur += ch
    if cur.strip():
        params.append(cur)
    return [p.strip() for p in params if p.strip()]


SIGNATURE = re.compile(
    r'(?P<prefix>(?:(?:public|private|protected|static|final|synchronized|native|abstract|default|strictfp)\s+)*)'
    r'(?P<type>[\w<>\[\],.?]+)\s+(?P<name>\w+)\s*\((?P<params>[^()]*)\)\s*(?:throws [\w., ]+)?\{')


def members(cls):
    flat = body_flat(strip(files[cls]))
    found = {}
    privates = set()
    for m in SIGNATURE.finditer(flat):
        name = m.group('name')
        if name in ('if', 'for', 'while', 'switch', 'catch', 'synchronized', 'new'):
            continue
        params = split_params(m.group('params'))
        arity = len(params)
        varargs = bool(params) and '...' in params[-1]
        found.setdefault(name, []).append({'arity': arity, 'varargs': varargs, 'static': 'static' in m.group('prefix')})
        if 'private' in m.group('prefix'):
            privates.add(name)
    return found, privates


decls, private_names = {}, {}
for cls in files:
    decls[cls], private_names[cls] = members(cls)

problems = []
for cls, raw in files.items():
    text = strip(raw)
    for m in re.finditer(r'\b([A-Z]\w*)\s*\.\s*(\w+)\s*\(', text):
        target, meth = m.group(1), m.group(2)
        if target == cls or target not in decls:
            continue
        if text[max(0, m.start() - 4):m.start()].strip().endswith('new'):
            continue  # new X.Y() anonymous class
        line = text[:m.start()].count('\n') + 1
        info = decls[target].get(meth)
        if info is None:
            problems.append(f'{cls}.java:{line} {target}.{meth}() -> no such method on {target}')
            continue
        if meth in private_names[target]:
            problems.append(f'{cls}.java:{line} {target}.{meth}() -> declared private in {target}')
            continue
        # count arguments at this call site
        i, depth, args, empty = m.end(), 1, 1, True
        while i < len(text) and depth:
            c = text[i]
            if c in '([{':
                depth += 1
            elif c in ')]}':
                depth -= 1
            elif c == ',' and depth == 1:
                args += 1
            if depth and not c.isspace() and c not in ',)':
                if depth >= 1:
                    empty = False
            i += 1
        n = 0 if (empty and args == 1) else args
        if not any(x['arity'] == n or (x['varargs'] and n >= x['arity'] - 1) for x in info):
            arities = sorted({x['arity'] for x in info})
            problems.append(f'{cls}.java:{line} {target}.{meth}({n} args) -> declared arity {arities}')

# static-context violations: a bare same-class call inside a static method to an instance method
for cls, raw in files.items():
    text = strip(raw)
    for m in re.finditer(r'\b([A-Z]\w*)\s*\.\s*(\w+)\s*\(', text):
        target, meth = m.group(1), m.group(2)
        if target not in decls:
            continue
        info = decls[target].get(meth)
        if not info:
            continue
        if any(x.get('static') for x in info):
            continue
        line = text[:m.start()].count('\n') + 1
        problems.append(f'{cls}.java:{line} {target}.{meth}() -> only instance methods exist; static context cannot use it')

for p in problems:
    print(p)
print(f'files={len(files)} private-declarations={sum(len(v) for v in private_names.values())} problems={len(problems)}')
raise SystemExit(1 if problems else 0)
