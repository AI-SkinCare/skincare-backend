import { disableTypes, imageSize, types } from 'image-size';

// image-size 2.x의 기본 진입점은 버퍼만 읽는다(파일 읽기는 image-size/fromFile로 분리) — 디스크에 닿지 않는다.
// 시그니처로 JPEG·PNG만 통과시키지만, 다른 형식 파서(HEIF·JXL·ICNS 등, DoS 권고 이력)는 아예 끈다.
disableTypes(types.filter((type) => type !== 'jpg' && type !== 'png'));

export type ImageMimeType = 'image/jpeg' | 'image/png';

/**
 * 확장자·mimetype이 아니라 파일 시그니처로 판별한다 (TRD §10.1).
 * JPEG `FF D8 FF`, PNG `89 50 4E 47`. HEIC·GIF 등은 null → 415 E2004.
 */
export function detectImageMimeType(buffer: Buffer): ImageMimeType | null {
  if (buffer.length >= 3 && buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) {
    return 'image/jpeg';
  }
  if (
    buffer.length >= 4 &&
    buffer[0] === 0x89 &&
    buffer[1] === 0x50 &&
    buffer[2] === 0x4e &&
    buffer[3] === 0x47
  ) {
    return 'image/png';
  }
  return null;
}

/** 헤더에서 가로·세로만 읽는다. 디코딩·저장 없음. 읽지 못하면 null */
export function readImageDimensions(buffer: Buffer): { width: number; height: number } | null {
  try {
    const { width, height } = imageSize(buffer);
    if (!width || !height) return null;
    return { width, height };
  } catch {
    return null;
  }
}
