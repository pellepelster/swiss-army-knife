import { Component, computed, inject, signal } from '@angular/core';
import { TranslatePipe } from '../i18n/i18n';
import { BucketStore } from './bucket-store';
import { BucketSecret, s3Endpoint } from './buckets.service';
import { CodeBlock } from './code-block';

// how the secret key is shown until it is asked for
const SECRET_MASK = '••••••••••••••••••••';

// the publishing tab of a bucket: how hugo deploys a site into it, the deployment target for the
// site's configuration and the command with the key pair of the bucket; the keys are only
// fetched when asked for, the secret stays masked until it is shown
@Component({
  selector: 'app-bucket-publishing',
  imports: [CodeBlock, TranslatePipe],
  template: `
    @if (store.secretError(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (store.bucket(); as bucket) {
      <section class="card card-body" [attr.aria-label]="'hosting.publishing.label' | t">
        <ol class="list-unstyled vstack gap-4 mb-0">
          <li class="vstack gap-2">
            <p class="mb-0">
              {{ 'hosting.publishing.target_before' | t }}
              <a
                href="https://gohugo.io/host-and-deploy/deploy-with-hugo-deploy/"
                target="_blank"
                rel="noopener"
                >Hugo</a
              >
              {{ 'hosting.publishing.target_after' | t }} <code>hugo.toml</code>.
              @if (bucket.domains.length) {
                {{ 'hosting.publishing.base_url' | t }}
              }
            </p>
            <app-code-block
              name="hugo.toml"
              [code]="configuration()"
              [copyLabel]="'hosting.publishing.copy_target' | t"
              testId="hugo-toml"
            />
          </li>
          <li class="vstack gap-2">
            <p class="mb-0">
              {{ 'hosting.publishing.deploy_intro' | t }} <code>hugo deploy</code>
              {{ 'hosting.publishing.deploy_only_before' | t }} <em>withdeploy</em>
              {{ 'hosting.publishing.deploy_only_after' | t }}
              <code>mise use hugo-extended-withdeploy</code>
              {{ 'hosting.publishing.deploy_archives' | t }}
              <a href="https://github.com/gohugoio/hugo/releases" target="_blank" rel="noopener">{{
                'hosting.publishing.releases' | t
              }}</a
              >.
            </p>
            @if (store.secret(); as secret) {
              <app-code-block
                name="hugo deploy"
                [code]="command(secret, !shown())"
                [copyText]="command(secret, false)"
                [copyLabel]="'hosting.publishing.copy_command' | t"
                testId="hugo-deploy"
                [command]="true"
              >
                <button
                  type="button"
                  class="btn btn-outline-light btn-sm"
                  [attr.aria-label]="
                    (shown()
                      ? 'hosting.publishing.hide_secret_key'
                      : 'hosting.publishing.show_secret_key'
                    ) | t
                  "
                  [attr.aria-pressed]="shown()"
                  (click)="shown.set(!shown())"
                >
                  <i
                    class="fa-solid"
                    [class.fa-eye-slash]="shown()"
                    [class.fa-eye]="!shown()"
                    aria-hidden="true"
                  ></i>
                  {{ (shown() ? 'hosting.publishing.hide' : 'hosting.publishing.show') | t }}
                </button>
              </app-code-block>
            } @else {
              <div>
                <button
                  type="button"
                  class="btn btn-primary btn-sm"
                  [disabled]="store.retrievingSecret()"
                  (click)="store.retrieveSecret()"
                >
                  @if (store.retrievingSecret()) {
                    <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
                  } @else {
                    <i class="fa-solid fa-key" aria-hidden="true"></i>
                  }
                  {{ 'hosting.publishing.show_command' | t }}
                </button>
              </div>
            }
          </li>
          <li class="vstack gap-2">
            <p class="text-body-secondary">
              {{ 'hosting.publishing.uploads' | t }}
            </p>
          </li>
        </ol>
      </section>
    }
  `,
})
export class BucketPublishing {
  protected readonly store = inject(BucketStore);

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
}
