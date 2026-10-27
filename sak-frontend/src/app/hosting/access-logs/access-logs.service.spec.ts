import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../auth/auth.service';
import { signedIn } from '../../testing/page';
import { AccessLogsService } from './access-logs.service';

describe('AccessLogsService', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('ends the session and leads to the login when the live log rejects it', async () => {
    signedIn();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideRouter([])] });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 401 })),
    );

    await expect(
      firstValueFrom(TestBed.inject(AccessLogsService).follow('site')),
    ).rejects.toThrow();

    expect(TestBed.inject(AuthService).token()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { redirect: '/' } });
  });
});
