const fs = require('node:fs');
const path = require('node:path');
const configured = process.env.REPAIR_FRONTEND_DIST;
if (!configured) throw Error('Set REPAIR_FRONTEND_DIST to the separately built frontend dist directory before running browser tests');
const dist = path.resolve(configured);
if (!fs.existsSync(path.join(dist, 'index.html')) || !fs.statSync(path.join(dist, 'assets'), { throwIfNoEntry: false })?.isDirectory()) {
  throw Error('REPAIR_FRONTEND_DIST must contain index.html and assets; run npm run build in the frontend repository first');
}
module.exports = dist;
