// Local isolated-server regression for #359/#361. SG_SERVER_DIR must point at
// the disposable server fixture, never a production server.
import fs from 'node:fs';
import path from 'node:path';
import { Vec3 } from 'vec3';
import { rcon, sleep, blockIs, placeAt } from './lib.js';

export async function verifyLiveConfigAndWand(bot, ck) {
  if (!process.env.SG_SERVER_DIR) throw new Error('SG_SERVER_DIR is required');
  const file = path.join(process.env.SG_SERVER_DIR, 'plugins/Spyglass/config.conf');
  const original = fs.readFileSync(file, 'utf8');
  const name = bot.username;
  try {
    await rcon(`clear ${name}`);
    await rcon(`gamemode survival ${name}`);
    await rcon('fill 65 80 66 65 81 66 air');
    bot.chat('/sg tool');
    await sleep(900);
    const wand = bot.inventory.items().find(i => i.name === 'redstone_lamp');
    ck(!!wand, 'inspection command gives a tagged lamp');
    await bot.equip(wand, 'hand');
    bot.chat('/sg tool');
    await sleep(500);
    await rcon(`deop ${name}`);
    try {
      await bot.placeBlock(bot.blockAt(new Vec3(65,79,66)), new Vec3(0,1,0));
    } catch (error) {
      console.log('WAND PLACE RESPONSE', error.message); // cancellation is expected; verify world state below
    }
    await sleep(700);
    ck(await blockIs(65,80,66,'air'), 'inactive wand cannot be placed by a non-op');
    await rcon(`op ${name}`);
    fs.writeFileSync(file, original + '\ntool.material = "GLOWSTONE"\nevents.break.enabled = false\n');
    ck((await rcon('sg reload')).includes('configuration reloaded'), 'reload applies wand material and event toggle');
    ck(!(await rcon('sg events')).match(/(?:^|[,:]\s*)break(?:,|$)/), 'disabled event removed from command catalog');
    try {
      await bot.placeBlock(bot.blockAt(new Vec3(65,79,66)), new Vec3(0,1,0));
    } catch (error) {
      console.log('WAND PLACE RESPONSE', error.message); // cancellation is expected; verify world state below
    }
    await sleep(700);
    ck(await blockIs(65,80,66,'air'), 'old material wand remains protected after reload');
    fs.writeFileSync(file, original + '\nstorage.queue-max = 12345\ntool.material = "STONE"\n');
    ck((await rcon('sg reload')).includes('restart required'), 'reload rejects pipeline changes');
    ck(!(await rcon('sg events')).match(/(?:^|[,:]\s*)break(?:,|$)/), 'rejected reload leaves previous live event toggles intact');
    fs.writeFileSync(file, original + '\nevents.break.retention = "garbage"\n');
    ck((await rcon('sg reload')).includes('could not reload'), 'invalid retention rejects reload');
    await rcon(`clear ${name}`);
    await rcon(`give ${name} redstone_lamp 1`);
    await sleep(500);
    console.log('CONTROL HAND', await rcon(`data get entity ${name} SelectedItem`));
    try {
      await placeAt(bot, 'redstone_lamp', 65,80,66);
      await sleep(700);
      ck(await blockIs(65,80,66,'redstone_lamp'), 'ordinary lamp can be placed at the same target');
    } catch (failure) {
      if (process.env.SG_BRIDGED_26_3 !== 'true') throw failure;
      // The same placement failed with Spyglass removed. Do not count this
      // protocol-bridge failure as evidence that the wand guard worked.
      console.log('LIMIT: native 26.3 client placement needs manual verification; bridged control failed without Spyglass too.');
      const probe = await rcon(`wandprobe ${name}`);
      console.log(probe);
      ck(probe.includes('PASS live Paper wand guards'), 'live Paper event guards distinguish tagged wand from ordinary lamp');
    }
    await rcon('setblock 65 80 66 air');
  } finally {
    fs.writeFileSync(file, original);
    ck((await rcon('sg reload')).includes('configuration reloaded'), 'original configuration restored live');
    await rcon(`op ${name}`);
    await rcon(`gamemode creative ${name}`);
    await rcon(`clear ${name}`);
  }
}
