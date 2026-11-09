#!/usr/bin/env node
/**
 * Generates the shared Live-voice tool schema (src/mobile/generatedTools.ts) from
 * MobileTools.java, which is the single source of truth for the Android tool set.
 *
 * Before this script the declarations were copy-pasted into src/mobile/actions.ts, which
 * drifted silently (a missing action was only caught late by a regex test). Now the Java
 * file is the only place to edit; `npm run check` verifies the generated file is current.
 *
 * run: node scripts/sync-tools.mjs [--check]
 */
import { readFileSync, writeFileSync } from 'node:fs';

const SOURCE = 'android/app/src/main/java/com/myraa/mobile/MobileTools.java';
const TARGET = 'src/mobile/generatedTools.ts';
const java = readFileSync(SOURCE, 'utf8');

/** Decode one Java string literal (only the escapes MobileTools actually uses). */
function unescape(literal) {
  return literal.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (_all, esc) => {
    if (esc[0] === 'u') return String.fromCharCode(parseInt(esc.slice(1), 16));
    switch (esc) {
      case 'n': return '\n';
      case 't': return '\t';
      case 'r': return '\r';
      case '"': return '"';
      case '\\': return '\\';
      default: return esc;
    }
  });
}

/** Split `json(...)` arguments at top-level commas. */
function splitArgs(text) {
  const out = [];
  let depth = 0, quote = false, current = '';
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quote) {
      current += c;
      if (c === '\\') { current += text[++i] ?? ''; continue; }
      if (c === '"') quote = false;
      continue;
    }
    if (c === '"') { quote = true; current += c; continue; }
    if (c === '(' || c === '[') depth++;
    if (c === ')' || c === ']') depth--;
    if (c === ',' && depth === 0) { out.push(current.trim()); current = ''; continue; }
    current += c;
  }
  if (current.trim()) out.push(current.trim());
  return out;
}

/** Parse a Java value expression into JSON-ish data. */
function parseValue(expr) {
  const value = expr.trim();
  if (value.startsWith('"')) {
    const m = /^"((?:[^"\\]|\\.)*)"/.exec(value);
    return m ? unescape(m[1]) : value;
  }
  if (/^(true|false)$/.test(value)) return value === 'true';
  if (/^-?\d+(\.\d+)?$/.test(value)) return Number(value);
  const call = /^(json|new JSONArray\(\))([\s\S]*)$/.exec(value);
  if (call) {
    if (call[1] === 'new JSONArray()') {
      const puts = [...value.matchAll(/\.put\("((?:[^"\\]|\\.)*)"\)/g)].map(m => unescape(m[1]));
      return puts;
    }
    const body = value.slice(value.indexOf('(') + 1, value.lastIndexOf(')'));
    const parts = splitArgs(body);
    if (parts.length % 2 !== 0) throw new Error(`json() with an odd argument count: ${value.slice(0, 120)}`);
    const object = {};
    for (let i = 0; i < parts.length; i += 2) {
      const keyPart = parts[i];
      const key = keyPart.startsWith('"') ? parseValue(keyPart) : keyPart;
      object[key] = parseValue(parts[i + 1]);
    }
    return object;
  }
  throw new Error(`Unsupported Java expression: ${value.slice(0, 120)}`);
}

/** Find `function(ACTION, "description", json(...), new String[]{...})` blocks. */
function declarations() {
  const constants = {};
  for (const m of java.matchAll(/static final String (\w+) = "([^"]+)";/g)) constants[m[1]] = m[2];
  const start = java.indexOf('public static JSONArray declarations()');
  const end = java.indexOf('public static String systemPrompt(');
  if (start < 0 || end < 0) throw new Error('declarations() or systemPrompt() not found in MobileTools.java');
  const block = java.slice(start, end);
  const tools = [];
  const marker = /function\(\s*(\w+)\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*json\(/g;
  let match;
  while ((match = marker.exec(block))) {
    const toolName = constants[match[1]];
    if (!toolName) throw new Error(`Unknown tool constant: ${match[1]}`);
    // Walk the JSON argument from just after `json(` to its matching close paren.
    let depth = 1, i = match.index + match[0].length, quote = false;
    for (; i < block.length && depth > 0; i++) {
      const c = block[i];
      if (quote) { if (c === '\\') i++; else if (c === '"') quote = false; continue; }
      if (c === '"') quote = true;
      else if (c === '(') depth++;
      else if (c === ')') depth--;
    }
    const properties = parseValue(`json(${block.slice(match.index + match[0].length, i - 1)})`);
    const requiredMatch = /new String\[\]\s*\{([^}]*)\}/.exec(block.slice(i, i + 400));
    const required = requiredMatch ? [...requiredMatch[1].matchAll(/"((?:[^"\\]|\\.)*)"/g)].map(m => unescape(m[1])) : [];
    tools.push({ name: toolName, description: unescape(match[2]), parameters: { type: 'OBJECT', properties, required } });
  }
  if (tools.length === 0) throw new Error('No tool declarations were found in MobileTools.java');
  return tools;
}

const tools = declarations();
const header = `/**
 * GENERATED FILE — do not edit.
 *
 * Produced by scripts/sync-tools.mjs from android/app/src/main/java/com/myraa/mobile/MobileTools.java
 * so text chat and Gemini Live can never drift apart. Edit MobileTools.java instead, then run:
 *   node scripts/sync-tools.mjs
 * 'npm run check' fails when this file is stale.
 */
export type ToolDeclaration = {
  name: string;
  description: string;
  parameters: { type: 'OBJECT'; properties: Record<string, unknown>; required: string[] };
};

export const GENERATED_TOOLS: ToolDeclaration[] = ${JSON.stringify(tools, null, 2)};
`;
const current = (() => { try { return readFileSync(TARGET, 'utf8'); } catch { return ''; } })();
if (current === header) { console.log(`${TARGET} is up to date (${tools.length} tools).`); process.exit(0); }
if (process.argv.includes('--check')) {
  console.error(`${TARGET} is stale. Run: node scripts/sync-tools.mjs`);
  process.exit(1);
}
writeFileSync(TARGET, header);
console.log(`Wrote ${TARGET} (${tools.length} tools: ${tools.map(t => t.name).join(', ')}).`);
