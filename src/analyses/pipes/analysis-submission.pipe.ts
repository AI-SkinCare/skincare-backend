import { ExecutionContext, Injectable, PipeTransform, createParamDecorator } from '@nestjs/common';
import type { Request } from 'express';
import { DEVICE_TYPES, DeviceType } from '../../common/enums';
import { ApiException } from '../../common/errors/api.exception';
import { ErrorCode, LOW_RESOLUTION_MESSAGE } from '../../common/errors/error-codes';
import { FieldError, validationException } from '../../common/validation/validation-errors';
import { MIN_IMAGE_SHORT_SIDE_PX } from '../../config/constants';
import { NormalizedSurvey, parseSurvey } from '../survey';
import { ImageMimeType, detectImageMimeType, readImageDimensions } from './image-signature';

export interface ValidatedImage {
  buffer: Buffer;
  mimeType: ImageMimeType;
  width: number;
  height: number;
}

export interface AnalysisSubmission {
  /** AI 측정 직후 null로 바꿔 버퍼 참조를 끊는다 */
  image: ValidatedImage | null;
  deviceType: DeviceType;
  survey: NormalizedSurvey;
}

interface RawSubmission {
  file: Express.Multer.File | undefined;
  body: Record<string, unknown>;
}

/** multer가 채운 req.file + 텍스트 필드를 한 번에 넘긴다 (검사 순서를 파이프 하나에서 지키기 위해) */
export const SubmissionParts = createParamDecorator(
  (_data: unknown, ctx: ExecutionContext): RawSubmission => {
    const req = ctx.switchToHttp().getRequest<Request>();
    const body = (typeof req.body === 'object' && req.body !== null ? req.body : {}) as Record<
      string,
      unknown
    >;
    return { file: req.file, body };
  },
);

/**
 * TRD §10.1 처리 순서 6 — 형식 검증.
 * image 없음 → E4002 / 시그니처가 JPEG·PNG 아님 → 415 E2004 /
 * 짧은 변 < 720px → 422 E2002 / deviceType·survey → E4002.
 * 이미지 내용·설문 원문은 오류 detail에 넣지 않는다.
 */
@Injectable()
export class AnalysisSubmissionPipe implements PipeTransform<RawSubmission, AnalysisSubmission> {
  transform({ file, body }: RawSubmission): AnalysisSubmission {
    if (!file || file.size === 0) {
      const fields = this.validateFields(body);
      throw validationException([{ field: 'image', reason: 'required' }, ...fields.errors]);
    }

    const mimeType = detectImageMimeType(file.buffer);
    if (!mimeType) throw new ApiException(ErrorCode.E2004);
    const dimensions = readImageDimensions(file.buffer);
    if (!dimensions) throw new ApiException(ErrorCode.E2004);
    if (Math.min(dimensions.width, dimensions.height) < MIN_IMAGE_SHORT_SIDE_PX) {
      throw new ApiException(ErrorCode.E2002, { message: LOW_RESOLUTION_MESSAGE });
    }

    const fields = this.validateFields(body);
    if (!fields.ok) throw validationException(fields.errors);
    return {
      image: { buffer: file.buffer, mimeType, ...dimensions },
      deviceType: fields.deviceType,
      survey: fields.survey,
    };
  }

  private validateFields(
    body: Record<string, unknown>,
  ):
    | { ok: true; deviceType: DeviceType; survey: NormalizedSurvey; errors: [] }
    | { ok: false; errors: FieldError[] } {
    const errors: FieldError[] = [];
    const deviceType = body.deviceType;
    if (deviceType === undefined || deviceType === '') {
      errors.push({ field: 'deviceType', reason: 'required' });
    } else if (!(DEVICE_TYPES as readonly unknown[]).includes(deviceType)) {
      errors.push({ field: 'deviceType', reason: 'invalid_value' });
    }
    const survey = parseSurvey(body.survey);
    if (!survey.ok) errors.push(...survey.errors);

    if (errors.length > 0 || !survey.ok) return { ok: false, errors };
    return { ok: true, deviceType: deviceType as DeviceType, survey: survey.survey, errors: [] };
  }
}
