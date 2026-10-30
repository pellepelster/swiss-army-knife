//! the answers of the window's commands for the screenshots and the video of sakctl's window
//! (`sak-e2e`, `docs/development/plans/screenshots.md`): what rust sends for a create of `example1` on
//! `krawallbu.de` in domain mode, built from the real types, texts and resources so the window
//! shows nothing rust would not send. Not a test of its own: `mise run docs:screenshots` and
//! `docs:videos` run it (`cargo test window_fixtures -- --ignored`) and the run writes
//! `SAK_WINDOW_FIXTURES` (`build/ui-fixtures/window.json` of the repository by default)

use super::*;
use crate::core::checks::{CheckEvent, CheckItem, Next};
use crate::core::providers::ProviderTokens;
use sak_cloud::api::Resource;
use sak_cloud::hetzner::deployment::{DeploymentStatus, ResourceState, RootDomain};
use serde_json::{Value, json};

const NAME: &str = "example1";
const ROOT_DOMAIN: &str = "krawallbu.de";
const PASSWORD: &str = "correct horse battery staple";
const LOCATION: Location = Location::hel1;
// addresses of the documentation ranges, no real server
const IPV4: &str = "203.0.113.10";
const IPV6: &str = "2001:db8:1::1";

/// the tasks of the playbook `pelle_io.sak.server` as its callback names them, in their order:
/// the pre tasks, then the roles common, storage, backend and role_markers
/// (ansible/roles/*/tasks/main.yml)
const TASKS: &[&str] = &[
    "Wait for the server to accept ssh",
    "Gather facts",
    "Remove the role markers of the run before",
    "Install unattended-upgrades",
    "Enable periodic package list updates and unattended upgrades",
    "Configure unattended upgrades",
    "Create the configuration directory of needrestart",
    "Configure needrestart to list only",
    "Install needrestart",
    "Enable the unattended-upgrades service",
    "Enable the periodic apt timers",
    "Harden sshd",
    "Install nftables",
    "Write the nftables rules",
    "Enable and start nftables",
    "Check that the data volume device is set",
    "Wait for the data volume device",
    "Ensure the data volume has a filesystem",
    "Create the mount point",
    "Mount the data volume",
    "Check that the required variables are set",
    "Install podman",
    "Create the sak group",
    "Create the data directory of the deployment",
    "Create the sak user",
    "Create the backend directory",
    "Create the data directory",
    "Create the s3 data directory",
    "Create the ingress data directory",
    "Create the ingress log directory",
    "Create the log data directories",
    "Pull the images",
    "Upload the vector configuration",
    "Write the backend configuration",
    "Upload the backend environment",
    "Stop the sak-controller container",
    "Remove the sak-controller quadlet and the image archives",
    "Install the quadlets",
    "Start the containers",
    "Create the directory of the role markers",
    "Write a marker for each role of the run",
];

/// an event of a channel and the milliseconds before it
fn after(delay_ms: u64, event: impl Serialize) -> Value {
    json!({ "delayMs": delay_ms, "message": event })
}

fn spec() -> DeploymentSpec {
    let public_key = crate::core::keys::derive_public_key(NAME, PASSWORD).unwrap();
    DeploymentSpec::new(NAME, &public_key, LOCATION, ServerType::cx23)
        .unwrap()
        .with_dns_mode(DnsMode::Domain)
        .with_root_domain(ROOT_DOMAIN)
        .with_password_hash(&crate::core::keys::password_hash(NAME, PASSWORD).unwrap())
}

/// the resources of the spec in the order the registry applies them: what they depend on first
fn resources(spec: &DeploymentSpec) -> Vec<sak_cloud::api::ResourceRef> {
    let mut resources = vec![
        spec.ssh_key().resource_ref(),
        spec.data_volume().resource_ref(),
        spec.ipv4().resource_ref(),
        spec.ipv6().resource_ref(),
        spec.firewall().resource_ref(),
        spec.server().resource_ref(),
    ];
    resources.extend(
        spec.dns_records()
            .iter()
            .map(|record| record.resource_ref()),
    );
    resources
}

fn provisioning(spec: &DeploymentSpec) -> String {
    t!("shared.provision.resource", name = spec.resource_name()).to_string()
}

/// the plan of the create (every resource missing, then the provisioning) or of a check of the
/// deployment as it is (a deploy: every resource up to date)
fn plan(spec: &DeploymentSpec, intent: Intent) -> PlanResponse {
    let status = if intent == Intent::Create {
        DiffStatus::Missing
    } else {
        DiffStatus::UpToDate
    };
    let created = |reference: &sak_cloud::api::ResourceRef| PlannedResource {
        resource: reference.log_text.clone(),
        kind: PlannedKind::Resource(ResourceKind::of(reference).expect("a resource of the spec")),
        name: reference.name.clone(),
        resource_status: ResourceStatus::of(status),
        action: action(intent, status, false, false, false),
        working: working(intent, status, false, false),
        status,
        keep: false,
        changes: Vec::new(),
    };
    let mut resources: Vec<PlannedResource> = resources(spec).iter().map(created).collect();
    resources.push(PlannedResource {
        resource: provisioning(spec),
        kind: PlannedKind::Provisioning,
        name: spec.resource_name(),
        resource_status: ResourceStatus::of(status),
        action: Some(if intent == Intent::Create {
            PlanAction::WillRun
        } else {
            PlanAction::RunsWhenForced
        }),
        working: working(intent, status, false, false),
        status,
        keep: false,
        changes: Vec::new(),
    });
    PlanResponse {
        name: NAME.to_string(),
        public_key: spec.public_key.clone(),
        resources,
    }
}

/// the create: each resource taken up and created, then the playbook task by task, the wait for
/// the deployment and the end
fn create(spec: &DeploymentSpec) -> Vec<Value> {
    let mut events = Vec::new();
    for reference in resources(spec) {
        let resource = reference.log_text.clone();
        // the server takes the longest at hetzner
        let busy = if ResourceKind::of(&reference) == Some(ResourceKind::Server) {
            2_500
        } else {
            700
        };
        events.push(after(
            150,
            DeploymentEvent::Started {
                resource: resource.clone(),
            },
        ));
        events.push(after(
            busy,
            DeploymentEvent::Applied {
                resource,
                text: applied_key(Applied::Created),
            },
        ));
    }
    let resource = provisioning(spec);
    events.push(after(
        150,
        DeploymentEvent::Started {
            resource: resource.clone(),
        },
    ));
    events.push(after(
        400,
        DeploymentEvent::Provisioning {
            message: t!(
                "shared.provision.running",
                name = spec.resource_name(),
                host = IPV4
            )
            .to_string(),
        },
    ));
    for (index, task) in TASKS.iter().enumerate() {
        events.push(after(
            150,
            DeploymentEvent::TaskStarted {
                resource: resource.clone(),
                task: task.to_string(),
            },
        ));
        // the checks and the facts change nothing, the rest sets the server up
        let outcome = if task.starts_with("Check")
            || task.starts_with("Gather")
            || task.starts_with("Wait")
        {
            TaskOutcome::Ok
        } else {
            TaskOutcome::Changed
        };
        let busy = match *task {
            "Wait for the server to accept ssh" | "Pull the images" | "Install podman" => 1_800,
            _ if index % 3 == 0 => 600,
            _ => 350,
        };
        events.push(after(
            busy,
            DeploymentEvent::TaskDone {
                resource: resource.clone(),
                task: task.to_string(),
                outcome,
                message: None,
            },
        ));
    }
    events.push(after(
        300,
        DeploymentEvent::Applied {
            resource,
            text: applied_key(Applied::Created),
        },
    ));
    let api = crate::core::config::api_url(&sak_cloud::hetzner::deployment::base_domain(
        NAME,
        DnsMode::Domain,
        ROOT_DOMAIN,
    ));
    events.push(after(
        2_000,
        DeploymentEvent::Provisioning {
            message: t!("shared.deployments.waiting_for_api", url = api.as_str()).to_string(),
        },
    ));
    events
}

/// `example1` after the create: every resource up to date, with the details a listing shows
fn created(spec: &DeploymentSpec) -> DeploymentResponse {
    let state = |reference: &sak_cloud::api::ResourceRef| {
        let kind = ResourceKind::of(reference).expect("a resource of a deployment");
        let detail = match kind {
            ResourceKind::SshKey => {
                Some("3c:5d:9e:1a:7f:20:b4:61:8e:c2:0d:95:4a:17:e3:b8".to_string())
            }
            ResourceKind::Volume => Some(format!("{} GB", spec.data_volume().size_gb)),
            ResourceKind::PrimaryIp if reference.name.ends_with("ipv6") => {
                Some(format!("{IPV6}/64"))
            }
            ResourceKind::PrimaryIp => Some(IPV4.to_string()),
            ResourceKind::Firewall => Some(format!("{} rules", spec.firewall().rules.len())),
            ResourceKind::Server => Some(format!("cx23 in {LOCATION}, {IPV4}")),
            _ => None,
        };
        ResourceState {
            kind,
            name: reference.name.clone(),
            status: Some(DiffStatus::UpToDate),
            changes: Vec::new(),
            detail,
        }
    };
    DeploymentResponse {
        provider: ProviderType::Hetzner.response(),
        name: NAME.to_string(),
        status: DeploymentStatus::Ok,
        location: Some(LOCATION),
        dns_mode: Some(DnsMode::Domain),
        root_domain: Some(ROOT_DOMAIN.to_string()),
        frontend_url: Some(format!("https://sak.{ROOT_DOMAIN}")),
        resources: resources(spec).iter().map(state).collect(),
        error: None,
    }
}

/// the checks without a token in the environment: the container runtime, the image, then the
/// providers page
fn checks() -> Vec<Value> {
    let checks =
        crate::core::checks::checks(crate::core::checks::ProviderTokensCheck::new(Vec::new()));
    let checks: Vec<_> = checks.into_iter().filter(|check| check.applies()).collect();
    let runtime = &checks[0];
    let image = &checks[1];
    let mut events = vec![after(
        200,
        CheckEvent::Listed {
            checks: checks
                .iter()
                .map(|check| CheckItem {
                    id: check.id(),
                    name: check.name(),
                })
                .collect(),
        },
    )];
    events.push(after(
        150,
        CheckEvent::Started {
            id: runtime.id(),
            name: runtime.name(),
        },
    ));
    events.push(after(
        700,
        CheckEvent::Passed {
            id: runtime.id(),
            name: runtime.name(),
            text: t!(
                "shared.checks.container_runtime.passed",
                runtime = "docker",
                version = "28.5.1"
            )
            .to_string(),
        },
    ));
    events.push(after(
        150,
        CheckEvent::Started {
            id: image.id(),
            name: image.name(),
        },
    ));
    for megabytes in [48, 127, 214, 296, 371] {
        events.push(after(
            350,
            CheckEvent::Progress {
                id: image.id(),
                fraction: megabytes as f64 / 371.0,
                text: t!(
                    "shared.checks.image.progress",
                    downloaded = megabytes,
                    total = 371
                )
                .to_string(),
            },
        ));
    }
    events.push(after(
        300,
        CheckEvent::Passed {
            id: image.id(),
            name: image.name(),
            text: t!(
                "shared.checks.image.passed",
                image = crate::core::container::image()
            )
            .to_string(),
        },
    ));
    events.push(after(
        100,
        CheckEvent::Finished {
            next: Next::Providers,
        },
    ));
    events
}

#[test]
#[ignore = "writes the fixtures of the window screenshots, run by mise run docs:screenshots"]
fn window_fixtures() {
    crate::core::i18n::init();
    let spec = spec();
    let token = ProviderTokens::default()
        .add(
            ProviderType::Hetzner,
            "kGq7XnL2cVb9WmR4tYp8sZ1aD6fH3jK5eQ0uI2oP7lN9xC4vB8mT1rE6yU3wA5sD",
        )
        .unwrap();
    let root_domain = RootDomain {
        name: ROOT_DOMAIN.to_string(),
        uses: Vec::new(),
    };
    let fixtures = json!({
        // each command answers with its list one after the other, the last one again and again
        "answers": {
            "locale": ["en"],
            "ui_texts": [crate::gui::i18n::ui_texts()],
            "list_provider_types": [crate::gui::providers::list_provider_types()],
            "list_provider_tokens": [[], [token]],
            "add_provider_token": [token],
            "list_deployments": [[], [created(&spec)]],
            "deployment_defaults": [DeploymentDefaults::default()],
            "check_name": [null],
            "locations": [crate::gui::deployments::locations()],
            "root_domains": [[root_domain_response(&root_domain, NAME)]],
            "plan_deployment": [plan(&spec, Intent::Create)],
            "check_deployment": [plan(&spec, Intent::Deploy)],
            "check_deployment_password": [null],
            "cancel_apply": [null],
            "apply_deployment": [null],
            "open_url": [null],
            "quit": [null],
            "run_checks": [null],
            "version": [crate::gui::update::version()],
        },
        // the events a command sends to its channel before it answers
        "channels": {
            "run_checks": checks(),
            "apply_deployment": create(&spec),
        },
    });
    let path = std::env::var("SAK_WINDOW_FIXTURES")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|_| {
            std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../../build/ui-fixtures/window.json")
        });
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(&path, serde_json::to_string_pretty(&fixtures).unwrap()).unwrap();
    println!("wrote {}", path.display());
}
