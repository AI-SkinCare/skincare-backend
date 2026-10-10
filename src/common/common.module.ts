import { Global, Module } from '@nestjs/common';
import { SLEEP, realSleep } from './utils/sleep';

@Global()
@Module({
  providers: [{ provide: SLEEP, useValue: realSleep }],
  exports: [SLEEP],
})
export class CommonModule {}
