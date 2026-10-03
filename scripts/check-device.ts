const modes: Record<string, string[]> = {
  'volume-fast': ['-e', 'volume', 'true', '-e', 'volumeFast', 'true'],
  volume: ['-e', 'volume', 'true'],
  button: ['-e', 'button', 'true'],
  'button-recording': ['-e', 'button', 'true', '-e', 'buttonRecording', 'true'],
  visual: ['-e', 'visual', 'true'],
  history: ['-e', 'history', 'true'],
  restore: ['-e', 'history', 'true', '-e', 'restore', 'true'],
}

export function fixtureArgs(mode: string): string[] {
  if (!Object.hasOwn(modes, mode)) throw new Error(`Choose a fixture: ${Object.keys(modes).join(', ')}.`)
  return modes[mode]
}

export function requireFixturePass(output: string, exitCode: number, mode: string) {
  const pass = /^INSTRUMENTATION_RESULT: stream=PASS:/m.test(output)
  const codes = output.match(/^INSTRUMENTATION_CODE:.*$/gm)
  const completed = codes?.length === 1 && codes[0].trim() === 'INSTRUMENTATION_CODE: -1'
  const failed = /\b(?:FAIL|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED):/.test(output)
  const fast = mode !== 'volume-fast' || /^INSTRUMENTATION_RESULT: stream=PASS: volume-fast:/m.test(output)
  if (exitCode !== 0 || !pass || !completed || failed || !fast)
    throw new Error('Fixture failed or did not report the requested success result. Check the test output and installed test APK.')
}

if (import.meta.main) {
  try {
    const [serial, mode, ...extra] = Bun.argv.slice(2)
    if (!serial || !/^emulator-\d+$/.test(serial))
      throw new Error('Select an explicit emulator-<port> device. Physical devices are not supported.')
    if (!mode || extra.length) throw new Error('Usage: bun scripts/check-device.ts emulator-<port> <fixture>')
    const args = fixtureArgs(mode)
    async function adb(...args: string[]) {
      const child = Bun.spawn([process.env.ADB || 'adb', '-s', serial, ...args], { stdout: 'pipe', stderr: 'pipe' })
      const [stdout, stderr, exitCode] = await Promise.all([
        new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited,
      ])
      return { stdout, stderr, exitCode }
    }
    const hardware = await adb('shell', 'getprop', 'ro.hardware')
    if (hardware.exitCode !== 0 || !['ranchu', 'goldfish'].includes(hardware.stdout.trim()))
      throw new Error('The selected device is not a connected Android emulator.')
    const unpaired = await adb('shell', "run-as dev.cameronpak.muser1 sh -c 'test ! -e no_backup/credentials.enc && test ! -e no_backup/credentials.enc.bak && test ! -e no_backup/credentials.enc.new && test ! -e files/pending-sdk-token'")
    if (unpaired.exitCode !== 0) throw new Error('Install the debug APK on an unprovisioned, disposable emulator before running fixtures.')
    const result = await adb('shell', 'am', 'instrument', '-w', '-r', ...args,
      'dev.cameronpak.muser1.test/dev.cameronpak.muser1.DeviceChecks')
    process.stdout.write(result.stdout)
    process.stderr.write(result.stderr)
    requireFixturePass(`${result.stdout}\n${result.stderr}`, result.exitCode, mode)
  } catch (error) {
    console.error(error instanceof Error ? error.message : 'Device check failed.')
    process.exitCode = 1
  }
}
