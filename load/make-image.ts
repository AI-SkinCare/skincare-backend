// npm run load:image — k6가 올릴 합성 이미지(load/.tmp/face.png, 1080×1440)를 만든다.
// stub 측정에서만 통과한다. 회귀 모델이 연결된 서버에는 본인이 동의한 얼굴 사진을 IMAGE로 준다 (커밋 금지).
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { solidPng } from '../test/fixtures/images';

const dir = join(__dirname, '.tmp');
mkdirSync(dir, { recursive: true });
writeFileSync(join(dir, 'face.png'), solidPng(1080, 1440));
console.log(`생성: ${join(dir, 'face.png')}`);
