// Export the approved ImageGen artwork; no image generation runs during an APK build.
// Install: npm install --prefix .tools/branding --cache .tools/npm-cache --save-exact sharp@0.34.5
const fs = require('node:fs/promises');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const sharp = require(path.join(root, '.tools/branding/node_modules/sharp'));
const assets = path.join(root, 'docs/assets/branding');
const res = path.join(root, 'app/src/main/res');
// Match the approved revision's outer graphite pixels so padding has no visible square seam.
const graphite = '#141A20';
const svg = (w, h, body) => Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${body}</svg>`);
const font = 'Microsoft YaHei, Noto Sans CJK SC, sans-serif';
const png = pipeline => pipeline.png({ compressionLevel: 9, palette: true, colours: 256, dither: 0.25 });
const rectMask = (size, radius) => svg(size, size, `<rect width="${size}" height="${size}" rx="${radius}" fill="white"/>`);
const circleMask = size => svg(size, size, `<circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}" fill="white"/>`);

async function main() {
  await fs.mkdir(assets, { recursive: true });
  const source = path.join(assets, 'mascot-source.webp');
  const metadata = await sharp(source).metadata();
  if (metadata.width !== metadata.height) throw new Error('Approved artwork must be square.');

  // Sibling desktop/GitHub tile: 9% outer inset, 23% radius relative to the tile.
  const tileSize = 840;
  const tile = await sharp(source).resize(tileSize, tileSize).ensureAlpha()
    .composite([{ input: rectMask(tileSize, Math.round(tileSize * 0.23)), blend: 'dest-in' }]).png().toBuffer();
  const icon = await png(sharp({ create: { width: 1024, height: 1024, channels: 4, background: '#00000000' } })
    .composite([{ input: tile, left: 92, top: 92 }])).toBuffer();
  await fs.writeFile(path.join(assets, 'app-icon.png'), icon);

  // Full 108dp layer, 60dp artwork, 24dp padding: neither layer bakes an OEM mask.
  const foreground = await sharp(source).resize(240, 240).toBuffer();
  const adaptive = await png(sharp({ create: { width: 432, height: 432, channels: 4, background: graphite } })
    .composite([{ input: foreground, left: 96, top: 96 }])).toBuffer();
  await fs.mkdir(path.join(res, 'drawable-nodpi'), { recursive: true });
  await fs.writeFile(path.join(res, 'drawable-nodpi/launcher_foreground_image.png'), adaptive);
  await fs.writeFile(path.join(res, 'drawable-nodpi/brand_app_icon.png'),
    await png(sharp(tile).resize(192, 192)).toBuffer());

  const hero = svg(1600, 640, `
    <defs><linearGradient id="bg" x2="1" y2="1"><stop stop-color="#25333E"/><stop offset="1" stop-color="#090E12"/></linearGradient></defs>
    <rect width="1600" height="640" rx="32" fill="url(#bg)"/>
    <path d="M1374 48h166v166M1508 48l-190 190" fill="none" stroke="#FFD16A" stroke-opacity=".12" stroke-width="22" stroke-linecap="round" stroke-linejoin="round"/>
    <g font-family="${font}">
      <text x="598" y="155" fill="#43D9EA" font-size="22" letter-spacing="4">XIAOLEXLDW / ANDROID</text>
      <text x="592" y="276" fill="#F4F8FA" font-size="88" font-weight="700">岛外打开</text>
      <text x="598" y="334" fill="#ADBCC5" font-size="29" letter-spacing="1">Mi Browser Redirector</text>
      <rect x="598" y="375" width="58" height="5" rx="2.5" fill="#FFD16A"/>
      <text x="598" y="438" fill="#E5EDF2" font-size="32">网页，交给你选的浏览器。</text>
      <text x="598" y="506" fill="#8EA3AF" font-size="22">小米 / HyperOS · Shizuku / Stellar</text>
      <text x="598" y="543" fill="#8EA3AF" font-size="20">实验性 Android 工具</text>
    </g>`);
  const heroPng = await png(sharp(hero).composite([{ input: await sharp(tile).resize(448, 448).toBuffer(), left: 80, top: 96 }])).toBuffer();
  await fs.writeFile(path.join(assets, 'readme-hero.png'), heroPng);

  const social = svg(1280, 640, `
    <defs><linearGradient id="bg" x2="1" y2="1"><stop stop-color="#25333E"/><stop offset="1" stop-color="#090E12"/></linearGradient></defs>
    <rect width="1280" height="640" fill="url(#bg)"/>
    <rect x="54" y="54" width="1172" height="532" rx="24" fill="none" stroke="#354550"/>
    <g font-family="${font}">
      <text x="528" y="163" fill="#43D9EA" font-size="20" letter-spacing="3">XIAOLEXLDW / ANDROID</text>
      <text x="523" y="278" fill="#F4F8FA" font-size="76" font-weight="700">岛外打开</text>
      <text x="528" y="333" fill="#ADBCC5" font-size="27">Mi Browser Redirector</text>
      <rect x="528" y="376" width="50" height="4" rx="2" fill="#FFD16A"/>
      <text x="528" y="434" fill="#E5EDF2" font-size="28">网页，交给你选的浏览器。</text>
      <text x="528" y="505" fill="#8EA3AF" font-size="21">HyperOS · Shizuku / Stellar · 实验性工具</text>
    </g>`);
  await png(sharp(social).composite([{ input: await sharp(tile).resize(366, 366).toBuffer(), left: 108, top: 137 }]))
    .toFile(path.join(assets, 'social-preview.png'));

  // Review actual Android resource pixels in the standard 72dp visible viewport.
  const viewport = await sharp(adaptive).extract({ left: 72, top: 72, width: 288, height: 288 }).png().toBuffer();
  const circle = await sharp(viewport).composite([{ input: circleMask(288), blend: 'dest-in' }]).png().toBuffer();
  const rounded = await sharp(viewport).composite([{ input: rectMask(288, 68), blend: 'dest-in' }]).png().toBuffer();
  // Render the actual monochrome vector, including its safety-zone group transform.
  const monoXml = await fs.readFile(path.join(res, 'drawable/ic_launcher_monochrome.xml'), 'utf8');
  const monoPaths = [...monoXml.matchAll(/<path\b[\s\S]*?\/>/g)].map(([tag]) => {
    const get = name => tag.match(new RegExp(`android:${name}="([^"]+)"`))?.[1];
    return `<path d="${get('pathData')}" fill="none" stroke="currentColor" stroke-width="${get('strokeWidth')}" stroke-linejoin="round" stroke-linecap="round"/>`;
  }).join('');
  const monoScale = Number(monoXml.match(/android:scaleX="([^"]+)"/)?.[1] || 1);
  const monochrome = (bg, ink) => Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="144" height="144" viewBox="18 18 72 72"><circle cx="54" cy="54" r="36" fill="${bg}"/><g color="${ink}" transform="translate(54 54) scale(${monoScale}) translate(-54 -54)">${monoPaths}</g></svg>`);
  const preview = svg(1440, 850, `<rect width="1440" height="850" fill="#EEF3F5"/>
    <g font-family="${font}" fill="#172027">
      <text x="60" y="76" font-size="36" font-weight="700">岛外打开 / 家族视觉</text>
      <text x="60" y="115" font-size="20" fill="#586870">浏览器窗口 + 向外打开箭头 · 石墨 / 青色 / 金色</text>
      <text x="89" y="486" font-size="23">仓库图标</text>
      <text x="453" y="486" font-size="23">Android 圆角裁切</text>
      <text x="805" y="486" font-size="23">Android 圆形裁切</text>
      <text x="1162" y="215" font-size="22">桌面尺寸</text>
      <text x="1158" y="318" font-size="17" fill="#586870">48 px</text>
      <text x="1270" y="318" font-size="17" fill="#586870">64 px</text>
      <text x="690" y="736" font-size="21">Android 13 单色主题图标</text>
      <text x="60" y="794" font-size="19" fill="#586870">静态素材预览；桌面动画与 HyperOS 实际显示待设备确认。</text>
    </g>`);
  await png(sharp(preview).composite([
    { input: await sharp(icon).resize(340).toBuffer(), left: 42, top: 143 },
    { input: rounded, left: 434, top: 172 }, { input: circle, left: 782, top: 172 },
    { input: await sharp(circle).resize(48).toBuffer(), left: 1160, top: 243 },
    { input: await sharp(circle).resize(64).toBuffer(), left: 1270, top: 235 },
    { input: await sharp(heroPng).resize(520, 208).toBuffer(), left: 60, top: 536 },
    { input: monochrome('#D3ECF0', '#164D59'), left: 690, top: 555 },
    { input: monochrome('#F4E4C8', '#645022'), left: 894, top: 555 },
  ])).toFile(path.join(assets, 'brand-preview.png'));
  for (const file of ['mascot-source.webp', 'app-icon.png', 'readme-hero.png', 'social-preview.png', 'brand-preview.png']) {
    const info = await sharp(path.join(assets, file)).metadata();
    const stat = await fs.stat(path.join(assets, file));
    if (stat.size > 1024 * 1024) throw new Error(`Asset exceeds repository size limit: ${file}`);
    console.log(`${file}: ${info.width}x${info.height}, ${stat.size} bytes`);
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
