import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { bucketErrorMessage, BucketStore } from './bucket-store';
import { BucketSecret, BucketsService, s3Endpoint } from './buckets.service';
import { CodeBlock } from './code-block';

// how the secret key is shown until it is asked for
export const SECRET_MASK = '••••••••••••••••••••';

// the publishing tab of a bucket: how hugo deploys a site into it, the deployment target for the
// site's configuration and the command with the key pair of the bucket; the keys are only
// fetched when asked for, the secret stays masked until it is shown
@Component({
  selector: 'app-bucket-publishing',
  imports: [CodeBlock],
  styleUrl: '../shared/app-parts.scss',
  styles: `
    .steps {
      display: grid;
      gap: 18px;
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .steps li {
      display: grid;
      gap: 8px;
      min-width: 0;
    }
    .steps p {
      margin: 0;
    }
    code.inline {
      font-family: ui-monospace, 'SFMono-Regular', Menlo, monospace;
      font-size: 0.9em;
    }
  `,
  template: `
    @if (error(); as error) {
      <p class="error" role="alert">{{ error }}</p>
    }

    @if (store.bucket(); as bucket) {
      <section class="sheet" aria-label="Publishing with Hugo">
        <ol class="steps">
          <li>
            <p>
              Add the bucket as deployment target to the configuration of the
              <a
                href="https://gohugo.io/host-and-deploy/deploy-with-hugo-deploy/"
                target="_blank"
                rel="noopener"
                >Hugo</a
              >
              site, <code class="inline">hugo.toml</code>.
              {{
                bucket.domains.length
                  ? 'The base URL is the first domain the bucket is served at.'
                  : ''
              }}
            </p>
            <app-code-block
              name="hugo.toml"
              [code]="configuration()"
              copyLabel="Copy the deployment target"
              testId="hugo-toml"
            />
          </li>
          <li>
            <p>
              Build the site and deploy it with the key pair of the bucket, which may access this
              bucket only. <code class="inline">hugo deploy</code> is only part of the
              <em>withdeploy</em> builds of Hugo, e.g.
              <code class="inline">mise use hugo-extended-withdeploy</code> or the withdeploy
              archives of the
              <a href="https://github.com/gohugoio/hugo/releases" target="_blank" rel="noopener"
                >Hugo releases</a
              >.
            </p>
            @if (secret(); as secret) {
              <app-code-block
                name="hugo deploy"
                [code]="command(secret, !shown())"
                [copyText]="command(secret, false)"
                copyLabel="Copy the deploy command"
                testId="hugo-deploy"
                [command]="true"
              >
                <button
                  type="button"
                  [attr.aria-label]="shown() ? 'Hide the secret key' : 'Show the secret key'"
                  [attr.aria-pressed]="shown()"
                  (click)="shown.set(!shown())"
                >
                  <i
                    class="fa-solid"
                    [class.fa-eye-slash]="shown()"
                    [class.fa-eye]="!shown()"
                    aria-hidden="true"
                  ></i>
                  {{ shown() ? 'Hide' : 'Show' }}
                </button>
              </app-code-block>
            } @else {
              <div>
                <button
                  type="button"
                  class="button button-small"
                  [disabled]="retrieving()"
                  (click)="retrieve()"
                >
                  @if (retrieving()) {
                    <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
                  } @else {
                    <i class="fa-solid fa-key" aria-hidden="true"></i>
                  }
                  Show the deploy command with the key pair
                </button>
              </div>
            }
          </li>
          <li>
            <p class="muted">
              It only uploads what changed and deletes what is gone. The site is served at the
              domains of the bucket once their DNS records point at the server.
            </p>
          </li>
        </ol>
      </section>
    }
  `,
})
export class BucketPublishing {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(BucketsService);
  protected readonly store = inject(BucketStore);

  protected readonly error = signal<string | null>(null);
  protected readonly secret = signal<BucketSecret | null>(null);
  protected readonly retrieving = signal(false);
  protected readonly shown = signal(false);

  // the deployment target of hugo (gocloud's s3 url), the region is only part of the signature
  protected readonly configuration = computed(() => {
    const bucket = this.store.bucket();
    if (!bucket) return '';
    return [
      ...(bucket.domains.length ? [`baseURL = "https://${bucket.domains[0]}/"`, ''] : []),
      '[deployment]',
      '[[deployment.targets]]',
      `name = "${bucket.name}"`,
      `URL = "s3://${bucket.name}?endpoint=${s3Endpoint()}&region=sak&use_path_style=true"`,
    ].join('\n');
  });

  protected command(secret: BucketSecret, masked: boolean): string {
    return [
      'hugo',
      `AWS_ACCESS_KEY_ID=${secret.accessKeyId} \\`,
      `AWS_SECRET_ACCESS_KEY=${masked ? SECRET_MASK : secret.secretAccessKey} \\`,
      `hugo deploy --target ${this.store.name()}`,
    ].join('\n');
  }

  protected retrieve(): void {
    this.retrieving.set(true);
    this.service
      .secret(this.store.name())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (secret) => {
          this.retrieving.set(false);
          this.secret.set(secret);
        },
        error: (error: unknown) => {
          this.retrieving.set(false);
          this.error.set(bucketErrorMessage(error, 'The key pair could not be fetched.'));
        },
      });
  }
}
