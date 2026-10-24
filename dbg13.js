const fs=require('fs'),path=require('path'),zlib=require('zlib');
function readPng(file){const buf=fs.readFileSync(file);let pos=8,w=0,h=0,color=0;const idat=[];
 while(pos<buf.length){const len=buf.readUInt32BE(pos);const type=buf.toString('ascii',pos+4,pos+8);const data=buf.subarray(pos+8,pos+8+len);pos+=12+len;
  if(type==='IHDR'){w=data.readUInt32BE(0);h=data.readUInt32BE(4);color=data[9];}else if(type==='IDAT')idat.push(data);else if(type==='IEND')break;}
 const ch={0:1,2:3,3:1,4:2,6:4}[color];const raw=zlib.inflateSync(Buffer.concat(idat));const stride=w*ch;const out=Buffer.alloc(w*h*ch);let prev=Buffer.alloc(stride);
 for(let y=0;y<h;y++){const ft=raw[y*(stride+1)];const line=raw.subarray(y*(stride+1)+1,(y+1)*(stride+1));const cur=Buffer.alloc(stride);
  for(let x=0;x<stride;x++){const a=x>=ch?cur[x-ch]:0,b=prev[x],c=x>=ch?prev[x-ch]:0;let v=line[x];
   if(ft===1)v=(v+a)&255;else if(ft===2)v=(v+b)&255;else if(ft===3)v=(v+((a+b)>>1))&255;
   else if(ft===4){const p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);v=(v+(pa<=pb&&pa<=pc?a:pb<=pc?b:c))&255;}
   cur[x]=v;}
  cur.copy(out,y*stride);prev=cur;}
 const rgba=Buffer.alloc(w*h*4);
 for(let i=0;i<w*h;i++){if(ch===4){rgba[i*4]=out[i*4];rgba[i*4+1]=out[i*4+1];rgba[i*4+2]=out[i*4+2];rgba[i*4+3]=out[i*4+3];}
  else if(ch===3){rgba[i*4]=out[i*3];rgba[i*4+1]=out[i*3+1];rgba[i*4+2]=out[i*3+2];rgba[i*4+3]=255;}
  else{const g=out[i*ch];rgba[i*4]=rgba[i*4+1]=rgba[i*4+2]=g;rgba[i*4+3]=255;}}
 return {w,h,data:rgba};}
const img=readPng(path.join(__dirname,'VeriBorder','tools/preview','hotel_reader.png'));
let x1=1e9,y1=1e9,x2=-1,y2=-1,n=0;
for(let y=0;y<img.h;y++)for(let x=0;x<img.w;x++){const i=(y*img.w+x)*4;
 if(img.data[i]>90&&img.data[i+1]<70&&img.data[i+2]>90){n++;if(x<x1)x1=x;if(x>x2)x2=x;if(y<y1)y1=y;if(y>y2)y2=y;}}
console.log('magenta pixels:',n, n?`bbox ${x1},${y1} .. ${x2},${y2}`:'(label not drawn at all)');
