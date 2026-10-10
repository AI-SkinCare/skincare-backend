import { gifHeader, headerOnlyJpeg, heicHeader, solidPng } from '../../../test/fixtures/images';
import { detectImageMimeType, readImageDimensions } from './image-signature';

describe('이미지 시그니처 (TRD §10.1)', () => {
  it('JPEG FF D8 FF / PNG 89 50 4E 47', () => {
    expect(detectImageMimeType(headerOnlyJpeg(10, 10))).toBe('image/jpeg');
    expect(detectImageMimeType(solidPng(10, 10))).toBe('image/png');
  });

  it('GIF·HEIC·빈 버퍼는 null → 415 E2004', () => {
    expect(detectImageMimeType(gifHeader())).toBeNull();
    expect(detectImageMimeType(heicHeader())).toBeNull();
    expect(detectImageMimeType(Buffer.alloc(0))).toBeNull();
  });

  it('헤더에서 가로·세로만 읽는다', () => {
    expect(readImageDimensions(headerOnlyJpeg(1080, 1440))).toEqual({ width: 1080, height: 1440 });
    expect(readImageDimensions(solidPng(600, 720))).toEqual({ width: 600, height: 720 });
    expect(readImageDimensions(Buffer.from([0xff, 0xd8, 0xff]))).toBeNull();
  });
});
