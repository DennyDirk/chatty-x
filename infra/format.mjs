import { readdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import * as prettier from '../frontend/node_modules/prettier/index.mjs';
import javaPlugin from '../frontend/node_modules/prettier-plugin-java/dist/index.mjs';
const root=new URL('../',import.meta.url);
async function visit(directory) {
  for(const entry of await readdir(directory,{withFileTypes:true})) {
    if(['node_modules','target','dist','.git','.idea'].includes(entry.name))continue;
    const path=new URL(entry.name+(entry.isDirectory()?'/':''),directory);
    if(entry.isDirectory())await visit(path);
    else if(/\.(java|tsx?|css|mjs|json)$/.test(entry.name)&&entry.name!=='package-lock.json') {
      const original=await readFile(path,'utf8');
      let formatted;
      try {formatted=await prettier.format(original,{filepath:fileURLToPath(path),plugins:[javaPlugin],printWidth:110,tabWidth:2});}
      catch {console.error('Formatter could not parse '+fileURLToPath(path));continue;}
      if(formatted!==original)await writeFile(path,formatted);
    }
  }
}
await visit(new URL('backend/src/',root));
await visit(new URL('frontend/src/',root));
console.log('Java, TypeScript and CSS formatted.');
