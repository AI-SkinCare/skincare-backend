import { Analysis } from '../analyses/entities/analysis.entity';
import { Recommendation } from '../analyses/entities/recommendation.entity';
import { SkinMetric } from '../analyses/entities/skin-metric.entity';
import { Survey } from '../analyses/entities/survey.entity';
import { RefreshToken } from '../auth/refresh-token.entity';
import { User } from '../users/user.entity';

export const ENTITIES = [User, RefreshToken, Analysis, SkinMetric, Survey, Recommendation];
