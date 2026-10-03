import { expect, test } from 'bun:test'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fixtureArgs, requireFixturePass } from './check-device'

test('fast and full volume checks reach the volume fixture with different cap coverage', () => {
  expect(fixtureArgs('volume-fast')).toEqual(['-e', 'volume', 'true', '-e', 'volumeFast', 'true'])
  expect(fixtureArgs('volume')).toEqual(['-e', 'volume', 'true'])
  expect(fixtureArgs('restore')).toEqual(['-e', 'history', 'true', '-e', 'restore', 'true'])
})

test.each(['cloud', 'voice', '', 'toString'])('rejects unsupported fixture %j', mode => {
  expect(() => fixtureArgs(mode)).toThrow()
})

test.each([
  ['FAIL: IllegalStateException', 0],
  ['PASS: layout\nFAIL: routing', 0],
  ['PASS: layout', 1],
  ['INSTRUMENTATION_CODE: 0', 0],
  ['Diagnostics mention PASS: but no result follows', 0],
  ['PASS: layout\nINSTRUMENTATION_FAILED: process crashed', 0],
  ['PASS: layout\nINSTRUMENTATION_ABORTED: interrupted', 0],
  ['PASS: layout', 0],
  ['PASS: layout\nINSTRUMENTATION_CODE: -1', 0],
  ['INSTRUMENTATION_RESULT: stream=PASS: layout', 0],
  ['INSTRUMENTATION_RESULT: stream=PASS: layout\nINSTRUMENTATION_CODE: 0', 0],
  ['INSTRUMENTATION_RESULT: stream=PASS: layout\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: 0', 0],
])('rejects incomplete or failed instrumentation even if adb exits zero (%j)', (output, exitCode) => {
  expect(() => requireFixturePass(output, exitCode, 'visual')).toThrow()
})

test('accepts a fixture result with Android RESULT_OK', () => {
  expect(() => requireFixturePass('INSTRUMENTATION_RESULT: stream=PASS: native views\nINSTRUMENTATION_CODE: -1\n', 0, 'visual')).not.toThrow()
})

test('fast mode refuses a stale test APK that silently runs the full fixture', () => {
  expect(() => requireFixturePass('INSTRUMENTATION_RESULT: stream=PASS: DPAD wheel media volume and limits\nINSTRUMENTATION_CODE: -1', 0, 'volume-fast')).toThrow()
  expect(() => requireFixturePass('INSTRUMENTATION_RESULT: stream=PASS: volume-fast: cap checks skipped\nINSTRUMENTATION_CODE: -1', 0, 'volume-fast')).not.toThrow()
})

test('CLI rejects physical devices before calling adb and does not echo their identifier', async () => {
  const child = Bun.spawn([process.execPath, 'scripts/check-device.ts', 'private-device-id', 'volume'], {
    stdout: 'pipe', stderr: 'pipe', env: { ...process.env, ADB: '/does-not-exist' },
  })
  const output = await new Response(child.stderr).text()
  expect(await child.exited).toBe(1)
  expect(output).toContain('explicit emulator-')
  expect(output).not.toContain('private-device-id')
})

test.each([null, 'no_backup/credentials.enc', 'no_backup/credentials.enc.bak', 'no_backup/credentials.enc.new', 'files/pending-sdk-token'])('CLI refuses credential-bearing files before instrumentation (%j)', async file => {
  const directory = mkdtempSync(join(tmpdir(), 'muse-runner-'))
  try {
    mkdirSync(join(directory, 'no_backup'))
    mkdirSync(join(directory, 'files'))
    if (file) writeFileSync(join(directory, file), '')
    const fakeAdb = join(directory, 'adb')
    writeFileSync(fakeAdb, `#!${process.execPath}
const args = Bun.argv.slice(2)
if (args[3] === 'getprop') console.log('ranchu')
else if (args[3].startsWith('run-as dev.cameronpak.muser1 ')) {
  const result = Bun.spawnSync(['sh', '-c', args[3].slice('run-as dev.cameronpak.muser1 '.length)])
  process.exit(result.exitCode)
} else if (args.includes('-r')) {
  console.log('INSTRUMENTATION_RESULT: stream=PASS: fixture')
  console.log('INSTRUMENTATION_CODE: -1')
}
`, { mode: 0o755 })
    const child = Bun.spawn([process.execPath, new URL('./check-device.ts', import.meta.url).pathname, 'emulator-5582', 'visual'], {
      cwd: directory, stdout: 'pipe', stderr: 'pipe', env: { ...process.env, ADB: fakeAdb },
    })
    const [stdout, stderr, exitCode] = await Promise.all([
      new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited,
    ])
    expect(exitCode).toBe(file ? 1 : 0)
    if (file) {
      expect(stderr).toContain('unprovisioned')
      expect(stdout).not.toContain('PASS:')
    } else expect(stdout).toContain('INSTRUMENTATION_CODE: -1')
  } finally { rmSync(directory, { recursive: true, force: true }) }
})
