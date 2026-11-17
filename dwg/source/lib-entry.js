import { LibreDwg, Dwg_File_Type } from '@mlightcad/libredwg-web';
import createModule from './node_modules/@mlightcad/libredwg-web/wasm/libredwg-web.js';
window.DwgLib = {
  Dwg_File_Type,
  async create(wasmBytes){ const inst = await createModule({ wasmBinary: wasmBytes, locateFile:(p)=>p, print:()=>{}, printErr:()=>{} }); return LibreDwg.createByWasmInstance(inst); }
};
