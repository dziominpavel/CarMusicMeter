/**
 * Собирает client.js из переводов translations/ru/*.json и сверяет их с translations/en/*.json.
 *
 * Запуск (из папки пакета): node tools/build.mjs
 * Записать client.js:      node tools/build.mjs --write
 */
import { readFileSync, readdirSync, writeFileSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const EN = join(root, 'translations/en')
const RU = join(root, 'translations/ru')
const OUT = join(root, 'client.js')

const namespaces = readdirSync(EN).map((f) => f.replace(/\.json$/, '')).sort()
const missing = []
const keyMismatch = []
const placeholderMismatch = []
const unchanged = []
const entries = []

for (const ns of namespaces) {
  const ruPath = join(RU, `${ns}.json`)
  if (!existsSync(ruPath)) { missing.push(ns); continue }
  const en = JSON.parse(readFileSync(join(EN, `${ns}.json`), 'utf8'))
  const ru = JSON.parse(readFileSync(ruPath, 'utf8'))
  const enKeys = Object.keys(en)
  const ruKeys = Object.keys(ru)
  const notTranslated = enKeys.filter((k) => !(k in ru))
  const extra = ruKeys.filter((k) => !(k in en))
  if (notTranslated.length || extra.length) {
    keyMismatch.push(`${ns}: missing=[${notTranslated.join(',')}] extra=[${extra.join(',')}]`)
  }
  for (const k of enKeys) {
    if (!(k in ru)) continue
    const placeholders = (s) => (String(s).match(/\{\w+\}/g) ?? []).sort().join('')
    if (placeholders(en[k]) !== placeholders(ru[k])) {
      placeholderMismatch.push(`${ns}.${k}: en="${en[k]}" ru="${ru[k]}"`)
    } else if (String(ru[k]).trim() === String(en[k]).trim() && /[A-Za-z]{3}/.test(String(en[k]))) {
      unchanged.push(`${ns}.${k}: ${en[k]}`)
    }
  }
  entries.push([ns, ru])
}

console.log(`namespaces_en=${namespaces.length} translated=${entries.length} missing=${missing.length}`)
if (missing.length) console.log('MISSING FILES: ' + missing.join(' '))
if (keyMismatch.length) { console.log('KEY MISMATCH:'); console.log(keyMismatch.join('\n')) }
if (placeholderMismatch.length) { console.log('PLACEHOLDER MISMATCH:'); console.log(placeholderMismatch.join('\n')) }
console.log(`unchanged_english=${unchanged.length}`)
if (unchanged.length) console.log(unchanged.join('\n'))

if (process.argv.includes('--write')) {
  if (missing.length || keyMismatch.length || placeholderMismatch.length) {
    console.error('refusing to write: fix the problems above first')
    process.exit(1)
  }
  const body = entries
    .map(([ns, dict]) => `      ${JSON.stringify(ns)}: ${JSON.stringify(dict, null, 2).split('\n').join('\n      ')},`)
    .join('\n')
  const src = `window.__ModuleLoader__.load({
  id: '@local/dsh-ru-locale',
  factory() {
    const dictionaries = {
${body}
    };
    return {
      inject: ['locale'],
      apply(ctx) {
        ctx.effect(
          () => ctx.locale.addLanguage({ id: 'ru', label: 'Русский', fallback: 'en' }),
          'ru-locale: language',
        );
        for (const [ns, dict] of Object.entries(dictionaries)) {
          ctx.effect(() => ctx.locale.register(ns, 'ru', dict), 'ru-locale: ' + ns);
        }
      },
    };
  },
});
`
  writeFileSync(OUT, src)
  console.log(`written ${OUT} (${Buffer.byteLength(src)} bytes)`)
}
