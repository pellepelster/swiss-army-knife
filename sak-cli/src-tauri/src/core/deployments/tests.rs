//! the tests of the deployments

use super::*;
use sak_cloud::hetzner::{DNS_MODE_LABEL, ROOT_DOMAIN_LABEL};

/// the page shows a plan by these words (`shared.resource_status.<status>`, `shared.plan.<action>`,
/// `shared.working.<working>` in run.js); each has its text
#[test]
fn the_words_of_a_plan_are_those_of_the_texts() {
    let word = |value: serde_json::Value| value.as_str().unwrap().to_string();
    for status in [
        ResourceStatus::UpToDate,
        ResourceStatus::Missing,
        ResourceStatus::HasChanges,
    ] {
        let key = format!("shared.resource_status.{}", word(serde_json::json!(status)));
        assert_eq!(key, status.text_key());
    }
    for action in [
        PlanAction::WillCreate,
        PlanAction::WillUpdate,
        PlanAction::WillDestroy,
        PlanAction::WillKeep,
        PlanAction::AlreadyDeleted,
        PlanAction::WillRun,
        PlanAction::RunsWhenForced,
    ] {
        let key = format!("shared.plan.{}", word(serde_json::json!(action)));
        assert_eq!(key, action.text_key());
    }
    for working in [
        Working::Create,
        Working::Update,
        Working::Recreate,
        Working::Check,
        Working::Destroy,
    ] {
        let key = format!("shared.working.{}", word(serde_json::json!(working)));
        assert_eq!(key, working.text_key());
    }
    // the kind of a planned resource is the word of its text
    assert_eq!(serde_json::json!(PlannedKind::Provisioning), "provisioning");
    assert_eq!(
        serde_json::json!(PlannedKind::Resource(ResourceKind::SshKey)),
        "ssh_key"
    );
    // and every key has a text
    let texts = crate::_rust_i18n_backend()
        .messages_for_locale("en")
        .unwrap_or_default();
    for key in [
        "shared.plan.will_run",
        "shared.working.working_check",
        "shared.done.created",
    ] {
        assert!(texts.iter().any(|(known, _)| &**known == key), "{key}");
    }
}

#[test]
fn the_start_of_an_apply_says_what_is_done_to_the_resource() {
    assert_eq!(
        working(Intent::Create, DiffStatus::Missing, false, false),
        Working::Create
    );
    assert_eq!(
        working(Intent::Deploy, DiffStatus::HasChanges, false, false),
        Working::Update
    );
    assert_eq!(
        working(Intent::Deploy, DiffStatus::HasChanges, true, false),
        Working::Recreate
    );
    assert_eq!(
        working(Intent::Deploy, DiffStatus::UpToDate, false, false),
        Working::Check
    );
    assert_eq!(
        working(Intent::Destroy, DiffStatus::UpToDate, false, false),
        Working::Destroy
    );
    // a dns record of the old dns mode goes on a deploy
    assert_eq!(
        working(Intent::Deploy, DiffStatus::UpToDate, false, true),
        Working::Destroy
    );
}

#[test]
fn the_password_of_a_deployment_is_checked_against_its_server_or_its_key() {
    let name = "alpha";
    let password = "a password of sixteen";
    let hash = crate::core::keys::password_hash(name, password).unwrap();
    let key = crate::core::keys::derive_public_key(name, password).unwrap();
    let server: sak_cloud::hetzner::api::servers::Server =
        serde_json::from_value(serde_json::json!({
            "id": 1, "name": name, "status": "running", "server_type": { "id": 1, "name": "cx23" },
            "image": { "id": 2, "name": "debian-13" }, "location": { "id": 3, "name": "hel1" },
            "protection": { "delete": false, "rebuild": false }, "volumes": [],
            "public_net": { "ipv4": null, "ipv6": null }, "private_net": [],
            "labels": { PASSWORD_HASH_LABEL: hash }
        }))
        .unwrap();
    let blob = key.split_whitespace().take(2).collect::<Vec<_>>().join(" ");
    let ssh_key: sak_cloud::hetzner::api::ssh_keys::SshKey =
        serde_json::from_value(serde_json::json!({
            "id": 7, "name": name, "fingerprint": "aa", "public_key": blob, "labels": {}
        }))
        .unwrap();

    let with_server = DiscoveredDeployment {
        name: name.to_string(),
        server: Some(server.into()),
        ..Default::default()
    };
    assert!(verify_password(&with_server, name, password).is_ok());
    assert!(verify_password(&with_server, name, "another password here").is_err());

    let with_key = DiscoveredDeployment {
        name: name.to_string(),
        ssh_key: Some(ssh_key.into()),
        ..Default::default()
    };
    assert!(verify_password(&with_key, name, password).is_ok());
    assert!(verify_password(&with_key, name, "another password here").is_err());
}

/// a deployment of `alpha` whose server carries the password hash of `password` and the labels
fn deployment_with(password: &str, labels: serde_json::Value) -> DiscoveredDeployment {
    let mut labels = labels;
    labels[PASSWORD_HASH_LABEL] =
        serde_json::json!(crate::core::keys::password_hash("alpha", password).unwrap());
    let server: sak_cloud::hetzner::api::servers::Server =
        serde_json::from_value(serde_json::json!({
            "id": 1, "name": "sak-alpha", "status": "running", "server_type": { "id": 1, "name": "cx23" },
            "image": { "id": 2, "name": "debian-13" }, "location": { "id": 3, "name": "nbg1" },
            "protection": { "delete": false, "rebuild": false }, "volumes": [],
            "public_net": { "ipv4": null, "ipv6": null }, "private_net": [],
            "labels": labels
        }))
        .unwrap();
    DiscoveredDeployment {
        name: "alpha".to_string(),
        server: Some(server.into()),
        ..Default::default()
    }
}

#[test]
fn config_show_reads_the_host_from_the_root_domain_and_dns_mode_labels() {
    let password = "a password of sixteen";
    let domain = deployment_with(
        password,
        serde_json::json!({ ROOT_DOMAIN_LABEL: "pelle.io", DNS_MODE_LABEL: "domain" }),
    );
    let subdomain = deployment_with(
        password,
        serde_json::json!({ ROOT_DOMAIN_LABEL: "pelle.io", DNS_MODE_LABEL: "subdomain" }),
    );

    let (url, key) = config_endpoint(&domain, "alpha", password).unwrap();
    assert_eq!(url, "https://api.pelle.io");
    assert_eq!(
        key.public_key(),
        crate::core::keys::api_key("alpha", password)
            .unwrap()
            .public_key()
    );
    assert_eq!(
        config_endpoint(&subdomain, "alpha", password).unwrap().0,
        "https://api.alpha.pelle.io"
    );
}

#[test]
fn config_show_refuses_a_wrong_password_before_any_request() {
    let deployment = deployment_with(
        "a password of sixteen",
        serde_json::json!({ ROOT_DOMAIN_LABEL: "pelle.io", DNS_MODE_LABEL: "domain" }),
    );
    // no address and no key come out of it, so nothing is sent
    assert_eq!(
        config_endpoint(&deployment, "alpha", "another password here").err(),
        Some(t!("shared.deployments.wrong_password", name = "alpha").to_string())
    );
}

#[test]
fn config_show_without_a_root_domain_label_says_so() {
    let password = "a password of sixteen";
    let deployment = deployment_with(password, serde_json::json!({ DNS_MODE_LABEL: "domain" }));
    assert_eq!(
        config_endpoint(&deployment, "alpha", password).err(),
        Some(t!("shared.config.no_root_domain", name = "alpha").to_string())
    );
}

#[test]
fn config_show_refuses_a_deployment_with_nothing_to_check_the_password_against() {
    let deployment = DiscoveredDeployment {
        name: "alpha".to_string(),
        ..Default::default()
    };
    assert_eq!(
        config_endpoint(&deployment, "alpha", "a password of sixteen").err(),
        Some(t!("shared.deployments.unverifiable", name = "alpha").to_string())
    );
}

#[test]
fn a_name_is_checked_once_and_a_wrong_one_is_a_usage_error() {
    assert_eq!(checked_name(" alpha-1 "), Ok("alpha-1"));
    for wrong in ["", "Alpha", "-alpha", "a_b", &"a".repeat(MAX_NAME_LEN + 1)] {
        assert_eq!(
            checked_name(wrong),
            Err(SakctlError::new(
                t!("shared.deployments.invalid_name", max = MAX_NAME_LEN),
                EXIT_USAGE
            )),
            "{wrong}"
        );
    }
}

#[test]
fn a_task_is_shown_without_its_role() {
    assert_eq!(
        task_name("pelle_io.sak.backend : Install the backend"),
        "Install the backend"
    );
    assert_eq!(task_name("Gathering Facts"), "Gathering Facts");
    assert!(matches!(
        task_event(
            "row",
            AnsibleEvent::TaskStart {
                task: "pelle_io.sak.common : Install nftables".into()
            }
        ),
        Some(DeploymentEvent::TaskStarted { task, .. }) if task == "Install nftables"
    ));
}

fn running(smtp_password: Option<&str>, password_set: bool) -> crate::core::config::RunningConfig {
    crate::core::config::RunningConfig {
        smtp: Some(crate::core::config::RunningSmtp {
            host: "smtp.example.org".into(),
            port: 465,
            username: Some("user".into()),
            password_set,
            password: smtp_password.map(String::from),
            from: "noreply@pelle.io".into(),
            tls: true,
            starttls: false,
        }),
        admins: vec!["admin@example.org".into()],
    }
}

fn deploy() -> DeploymentRequest {
    DeploymentRequest {
        name: "alpha".into(),
        password: "p".into(),
        password_repeat: "p".into(),
        location: Location::nbg1,
        dns_mode: None,
        root_domain: None,
        intent: Intent::Deploy,
        delete_data: false,
        user_mode: None,
        acme_ca: None,
        smtp: None,
        admin_email: None,
        force: true,
    }
}

#[tokio::test]
async fn nothing_is_created_in_a_location_this_sakctl_does_not_know() {
    let unknown = DeploymentRequest {
        location: Location::unknown,
        ..deploy()
    };
    let Err(error) = DeploymentManager.prepare(Vec::new(), &unknown).await else {
        panic!("a deploy into an unknown location was prepared");
    };
    assert_eq!(error.exit_code, crate::core::error::EXIT_USAGE);
    assert!(error.message.contains("alpha"), "{}", error.message);
}

#[tokio::test]
async fn a_wrong_name_is_a_usage_error_before_the_provider_is_asked() {
    let manager = DeploymentManager;
    let error = manager
        .check_password(Vec::new(), "Not A Name", "p")
        .await
        .unwrap_err();
    assert_eq!(error.exit_code, crate::core::error::EXIT_USAGE);
    // a valid name without a provider token fails as such
    let error = manager
        .check_password(Vec::new(), "alpha", "p")
        .await
        .unwrap_err();
    assert_eq!(error.exit_code, crate::core::error::EXIT_FAILED);
    assert_eq!(
        error.message,
        t!("shared.deployments.no_provider").to_string()
    );
}

#[tokio::test]
async fn a_deploy_is_completed_only_when_something_is_not_given_and_a_failed_read_is_the_error() {
    // no provider token: a deploy that needs the deployment read fails, one that does not
    // never asks
    let single = DeploymentRequest {
        user_mode: Some(UserMode::Single),
        ..deploy()
    };
    let completed = DeploymentManager
        .completed(Vec::new(), single, "")
        .await
        .unwrap();
    assert_eq!(completed.user_mode, Some(UserMode::Single));

    let given = DeploymentRequest {
        user_mode: Some(UserMode::Multi),
        admin_email: Some("admin@example.com".into()),
        smtp: Some(Smtp {
            host: "smtp.example.com".into(),
            port: 587,
            username: "sak".into(),
            password: "secret".into(),
            starttls: true,
        }),
        ..deploy()
    };
    assert!(
        DeploymentManager
            .completed(Vec::new(), given, "")
            .await
            .is_ok()
    );

    // the mail server is missing: the deployment is read, and that it cannot be is the error,
    // not a missing mail server later
    let Err(error) = DeploymentManager.completed(Vec::new(), deploy(), "").await else {
        panic!("a deploy without its mail server was completed without the deployment");
    };
    assert_eq!(
        error.message,
        t!("shared.deployments.no_provider").to_string()
    );
}

#[test]
fn a_backend_that_does_not_answer_is_the_error_when_its_settings_are_needed() {
    let unread = |user_mode| Detected {
        user_mode,
        unread: Some("connection refused".to_string()),
        ..Detected::default()
    };
    // multi user on the label, the mail server unknown: the cause, not "invalid smtp host"
    let error = match completed_from(deploy(), &unread(Some(UserMode::Multi)), "") {
        Err(error) => error.message,
        Ok(_) => panic!("completed without the mail server"),
    };
    assert!(
        error.contains("alpha") && error.contains("connection refused"),
        "{error}"
    );
    // no label: the user mode itself is unknown
    assert!(completed_from(deploy(), &unread(None), "").is_err());
    // what is given needs nothing of the backend
    let given = DeploymentRequest {
        user_mode: Some(UserMode::Multi),
        admin_email: Some("admin@example.com".into()),
        smtp: Some(Smtp {
            host: "smtp.example.com".into(),
            port: 587,
            username: "sak".into(),
            password: "secret".into(),
            starttls: true,
        }),
        ..deploy()
    };
    assert!(completed_from(given, &unread(Some(UserMode::Multi)), "").is_ok());
    let single = DeploymentRequest {
        user_mode: Some(UserMode::Single),
        ..deploy()
    };
    assert!(completed_from(single, &unread(None), "").is_ok());
}

#[test]
fn the_user_mode_is_detected_from_the_label_else_from_the_running_configuration() {
    // the label wins
    assert_eq!(
        detect(Some(UserMode::Single), Some(&running(None, false))).user_mode,
        Some(UserMode::Single)
    );
    // without it: a mail server makes it multi user, none single user, nothing running nothing
    let multi = detect(None, Some(&running(Some("secret"), true)));
    assert_eq!(multi.user_mode, Some(UserMode::Multi));
    assert_eq!(multi.admin_email.as_deref(), Some("admin@example.org"));
    let smtp = multi.smtp.unwrap();
    assert_eq!(smtp.password.as_deref(), Some("secret"));
    // the password never shows, not in a {:?} either
    assert!(!format!("{smtp:?}").contains("secret"));
    assert!(!format!("{:?}", running(Some("secret"), true)).contains("secret"));
    let without_mail = crate::core::config::RunningConfig {
        smtp: None,
        admins: vec![],
    };
    assert_eq!(
        detect(None, Some(&without_mail)).user_mode,
        Some(UserMode::Single)
    );
    assert_eq!(detect(None, None), Detected::default());
    // single user: no admin and no mail server even when the backend has them
    assert_eq!(
        detect(Some(UserMode::Single), Some(&running(None, false))).smtp,
        None
    );
}

#[test]
fn a_deploy_takes_what_it_is_not_told_from_the_detected_settings() {
    let detected = detect(Some(UserMode::Multi), Some(&running(Some("secret"), true)));

    let completed = complete(deploy(), &detected, "").unwrap();
    assert_eq!(completed.user_mode, Some(UserMode::Multi));
    assert_eq!(completed.admin_email.as_deref(), Some("admin@example.org"));
    let smtp = completed.smtp.unwrap();
    assert_eq!(
        (
            smtp.host.as_str(),
            smtp.port,
            smtp.username.as_str(),
            smtp.password.as_str()
        ),
        ("smtp.example.org", 465, "user", "secret")
    );
    // what is given wins: the mode, the admin, the password of the mail server
    let mut single = deploy();
    single.user_mode = Some(UserMode::Single);
    assert!(complete(single, &detected, "").unwrap().smtp.is_none());
    let mut own = deploy();
    own.admin_email = Some("other@example.org".into());
    assert_eq!(
        complete(own, &detected, "").unwrap().admin_email.as_deref(),
        Some("other@example.org")
    );
    assert_eq!(
        complete(deploy(), &detected, "given")
            .unwrap()
            .smtp
            .unwrap()
            .password,
        "given"
    );
    // a backend of before tells no password: it has to be given
    let before = detect(Some(UserMode::Multi), Some(&running(None, true)));
    assert_eq!(
        complete(deploy(), &before, "")
            .err()
            .map(|error| error.exit_code),
        Some(crate::core::error::EXIT_USAGE)
    );
}

#[test]
fn the_tasks_of_the_playbook_show_on_the_row_of_the_provisioning() {
    let row = "provisioning 'sak-alpha'";
    let done = |event| match task_event(row, event) {
        Some(DeploymentEvent::TaskDone {
            resource,
            outcome,
            message,
            ..
        }) => {
            assert_eq!(resource, row);
            (outcome, message)
        }
        _ => panic!("no task done"),
    };
    let ok = |changed| AnsibleEvent::Ok {
        host: "alpha".into(),
        task: "t".into(),
        changed,
    };
    assert_eq!(done(ok(false)), (TaskOutcome::Ok, None));
    assert_eq!(done(ok(true)), (TaskOutcome::Changed, None));
    let failed = |ignore_errors| AnsibleEvent::Failed {
        host: "alpha".into(),
        task: "t".into(),
        msg: Some("no".into()),
        ignore_errors,
    };
    assert_eq!(
        done(failed(false)),
        (TaskOutcome::Failed, Some("no".into()))
    );
    assert_eq!(
        done(failed(true)),
        (TaskOutcome::Ignored, Some("no".into()))
    );
    assert!(matches!(
        task_event(row, AnsibleEvent::TaskStart { task: "t".into() }),
        Some(DeploymentEvent::TaskStarted { task, .. }) if task == "t"
    ));
    assert!(task_event(row, AnsibleEvent::PlayStart { play: "p".into() }).is_none());
    // the window reads the events by these names
    assert_eq!(
        serde_json::to_value(DeploymentEvent::ProvisionFailed {
            resource: row.into(),
            task: None,
            message: "no".into()
        })
        .unwrap()["event"],
        "provisionFailed"
    );
    assert_eq!(
        serde_json::to_value(TaskOutcome::Unreachable).unwrap(),
        "unreachable"
    );
}

#[test]
fn every_hash_on_the_resources_has_to_match() {
    let (name, password) = ("alpha", "a password of sixteen");
    let hash = crate::core::keys::password_hash(name, password).unwrap();
    let other = crate::core::keys::password_hash(name, "another password here").unwrap();
    assert!(check_password(&[hash.clone(), hash.clone()], None, name, password).is_ok());
    // one resource of another password is enough to stop
    assert!(check_password(&[hash.clone(), other], None, name, password).is_err());
    // a hash wins over the key, a key of another password is not asked
    assert!(check_password(&[hash], Some("ssh-ed25519 AAAA"), name, password).is_ok());
    // nothing to check against, a data volume left without a hash: refused, not any password
    assert_eq!(
        check_password(&[], None, name, password),
        Err(t!("shared.deployments.unverifiable", name = name).to_string())
    );
}

#[test]
fn the_user_data_installs_the_host_key() {
    let host_key = crate::core::keys::derive_host_key("alpha", "a password of sixteen").unwrap();
    let user_data = user_data(&host_key);
    assert!(user_data.starts_with("#cloud-config\nssh_deletekeys: true\nssh_genkeytypes: []\nssh_keys:\n  ed25519_private: |\n    -----BEGIN OPENSSH PRIVATE KEY-----\n"));
    assert!(user_data.ends_with(&format!("  ed25519_public: {}\n", host_key.public_key())));
}

fn uses(name: &str, uses: &[(Option<&str>, DnsMode)]) -> RootDomain {
    RootDomain {
        name: name.to_string(),
        uses: uses
            .iter()
            .map(
                |(name, dns_mode)| sak_cloud::hetzner::deployment::DomainUse {
                    name: name.map(str::to_string),
                    dns_mode: *dns_mode,
                },
            )
            .collect(),
    }
}

/// pelle.io managed by beta as domain, example.com shared by subdomain deployments,
/// free.org used by nobody
fn domains() -> Vec<RootDomain> {
    vec![
        uses("pelle.io", &[(Some("beta"), DnsMode::Domain)]),
        uses(
            "example.com",
            &[
                (Some("gamma"), DnsMode::Subdomain),
                (None, DnsMode::Subdomain),
            ],
        ),
        uses("free.org", &[]),
    ]
}

#[test]
fn a_root_domain_must_be_a_zone_of_the_project() {
    // a failure of its own, not one of the root domain conflicts
    let en = |result: Result<String, SakctlError>| {
        let error = result.unwrap_err();
        assert_eq!(error.exit_code, crate::core::error::EXIT_FAILED);
        error.message
    };
    assert_eq!(
        en(check_root_domain(
            &[],
            Some("pelle.io"),
            "alpha",
            DnsMode::Subdomain
        )),
        t!("shared.deployments.no_zones")
    );
    assert_eq!(
        en(check_root_domain(
            &domains(),
            None,
            "alpha",
            DnsMode::Subdomain
        )),
        t!(
            "shared.deployments.no_root_domain",
            domains = "pelle.io, example.com, free.org"
        )
    );
    assert_eq!(
        en(check_root_domain(
            &domains(),
            Some("  "),
            "alpha",
            DnsMode::Subdomain
        )),
        t!(
            "shared.deployments.no_root_domain",
            domains = "pelle.io, example.com, free.org"
        )
    );
    assert_eq!(
        en(check_root_domain(
            &domains(),
            Some("other.net"),
            "alpha",
            DnsMode::Subdomain
        )),
        t!(
            "shared.deployments.unknown_root_domain",
            domain = "other.net",
            domains = "pelle.io, example.com, free.org"
        )
    );
    assert_eq!(
        check_root_domain(&domains(), Some(" free.org "), "alpha", DnsMode::Domain),
        Ok("free.org".to_string())
    );
}

#[test]
fn a_root_domain_of_a_domain_deployment_stops_every_other_deployment() {
    assert_eq!(
        check_root_domain(&domains(), Some("pelle.io"), "alpha", DnsMode::Domain),
        Err(SakctlError::new(
            t!(
                "shared.deployments.domain_taken",
                domain = "pelle.io",
                by = "beta"
            ),
            EXIT_DOMAIN_TAKEN
        ))
    );
    assert_eq!(
        check_root_domain(&domains(), Some("pelle.io"), "alpha", DnsMode::Subdomain),
        Err(SakctlError::new(
            t!(
                "shared.deployments.domain_mixed",
                domain = "pelle.io",
                by = "beta"
            ),
            EXIT_MIXED_DNS_MODES
        ))
    );
    // beta itself deploys again, or changes to subdomain
    for mode in DnsMode::ALL {
        assert!(check_root_domain(&domains(), Some("pelle.io"), "beta", mode).is_ok());
    }
}

#[test]
fn a_root_domain_of_subdomain_deployments_takes_only_the_subdomain_mode() {
    let by = format!("gamma, {}", t!("shared.deployments.unnamed"));
    assert_eq!(
        check_root_domain(&domains(), Some("example.com"), "alpha", DnsMode::Domain),
        Err(SakctlError::new(
            t!(
                "shared.deployments.subdomain_only",
                domain = "example.com",
                by = by
            ),
            EXIT_MIXED_DNS_MODES
        ))
    );
    assert_eq!(
        check_root_domain(&domains(), Some("example.com"), "alpha", DnsMode::Subdomain),
        Ok("example.com".to_string())
    );
}

#[test]
fn the_page_gets_the_modes_of_each_root_domain_and_why_one_is_not_possible() {
    let responses: Vec<RootDomainResponse> = domains()
        .iter()
        .map(|domain| root_domain_response(domain, "alpha"))
        .collect();
    assert!(responses[0].dns_modes.is_empty());
    assert_eq!(
        responses[0].note.as_deref(),
        Some(
            t!(
                "shared.deployments.domain_taken",
                domain = "pelle.io",
                by = "beta"
            )
            .as_ref()
        )
    );
    assert_eq!(responses[1].dns_modes, vec![DnsMode::Subdomain]);
    assert!(responses[1].note.is_some());
    assert_eq!(responses[2].dns_modes, DnsMode::ALL.to_vec());
    assert_eq!(responses[2].note, None);
    assert_eq!(
        serde_json::to_value(&responses[1]).unwrap(),
        serde_json::json!({
            "name": "example.com",
            "dnsModes": ["subdomain"],
            "note": responses[1].note,
        })
    );
}

fn smtp(host: &str, port: u16) -> Smtp {
    Smtp {
        host: host.to_string(),
        port,
        username: "user".to_string(),
        password: "secret".to_string(),
        starttls: true,
    }
}

#[test]
fn an_address_is_plain_enough_for_backend_env_and_yaml() {
    for address in [
        "admin@example.org",
        "first.last+sak@mail.example.co.uk",
        " a@b.de ",
    ] {
        assert!(is_address(address), "{address}");
    }
    for address in [
        "",
        "admin",
        "@example.org",
        "admin@",
        "admin@example",
        "a@b@example.org",
        "admin@.example.org",
        "admin@example.org.",
        "ad min@example.org",
        "admin@example.org\nSAK_X=1",
        "admin:x@example.org",
        &format!("{}@example.org", "a".repeat(MAX_ADDRESS_LEN)),
    ] {
        assert!(!is_address(address), "{address:?}");
    }
}

#[test]
fn a_user_mode_is_single_or_multi() {
    assert_eq!(UserMode::parse("single"), Some(UserMode::Single));
    assert_eq!(UserMode::parse(" Multi "), Some(UserMode::Multi));
    assert_eq!(UserMode::parse("many"), None);
}

#[test]
fn a_value_with_a_line_break_is_refused() {
    let mut with_break = smtp("smtp.example.org", 587);
    with_break.password = "secret\nSAK_ADMIN_PASSWORD_HASH=x".to_string();
    assert_eq!(
        check_smtp(&with_break),
        Err(t!("shared.deployments.invalid_smtp_value").to_string())
    );
}

#[test]
fn a_mail_server_needs_a_host_and_a_port() {
    assert!(check_smtp(&smtp("smtp.example.org", 587)).is_ok());
    assert!(check_smtp(&smtp(" smtp.example.org ", 465)).is_ok());
    assert_eq!(
        check_smtp(&smtp("", 587)),
        Err(t!("shared.deployments.invalid_smtp_host").to_string())
    );
    assert!(check_smtp(&smtp("smtp example.org", 587)).is_err());
    assert!(check_smtp(&smtp(&"a".repeat(MAX_HOST_LEN + 1), 587)).is_err());
    assert_eq!(
        check_smtp(&smtp("smtp.example.org", 0)),
        Err(t!("shared.deployments.invalid_smtp_port").to_string())
    );
}

#[test]
fn a_flag_is_true_or_false_in_the_usual_spellings() {
    for value in ["true", "TRUE", "1", "yes", "on"] {
        assert_eq!(parse_flag(value), Ok(true), "{value}");
    }
    for value in ["false", "0", "no", "Off"] {
        assert_eq!(parse_flag(value), Ok(false), "{value}");
    }
    assert!(parse_flag("maybe").is_err());
}

#[test]
fn the_page_gets_the_mail_server_of_the_environment() {
    let defaults = DeploymentDefaults {
        name: None,
        password: None,
        location: None,
        smtp_host: Some("smtp.example.org".to_string()),
        smtp_port: Some("465".to_string()),
        smtp_username: Some("user".to_string()),
        smtp_password: Some("secret".to_string()),
        smtp_starttls: Some(false),
        admin_email: Some("admin@example.org".to_string()),
        user_mode: Some(UserMode::Multi),
    };
    let json = serde_json::to_value(&defaults).unwrap();
    assert_eq!(json["userMode"], "multi");
    assert_eq!(json["smtpHost"], "smtp.example.org");
    assert_eq!(json["smtpPort"], "465");
    assert_eq!(json["smtpStarttls"], false);
    // and reads it back from the page, the user name and password may be left out
    let page: Smtp = serde_json::from_value(serde_json::json!({
        "host": "smtp.example.org", "port": 587, "starttls": true
    }))
    .unwrap();
    assert_eq!((page.port, page.username.as_str()), (587, ""));
}

#[test]
fn a_resource_of_someone_else_ends_with_its_own_exit_code() {
    let not_owned = cloud_failure(
        "shared.deployments.destroy_failed",
        ProviderType::Hetzner,
        sak_cloud::api::Error::NotOwned {
            resource: "server 'web'".to_string(),
            reason: "it has no label 'sak.pelle.io/name'".to_string(),
        },
    );
    assert_eq!(not_owned.exit_code, EXIT_NOT_OWNED);
    assert!(not_owned.message.starts_with(
        "server 'web' exists and does not belong to this deployment: it has no label 'sak.pelle.io/name'"
    ));
    let refused = cloud_failure(
        "shared.deployments.create_failed",
        ProviderType::Hetzner,
        sak_cloud::api::Error::RecreateRefused {
            resource: "volume 'web-data'".to_string(),
            changes: "location should be 'hel1' but was 'nbg1'".to_string(),
        },
    );
    assert_eq!(refused.exit_code, EXIT_RECREATE_REFUSED);
    assert!(
        refused
            .message
            .starts_with("volume 'web-data' would have to be created anew")
    );
    // any other failure of the provider is a failure as before
    let other = cloud_failure(
        "shared.deployments.destroy_failed",
        ProviderType::Hetzner,
        sak_cloud::api::Error::message("timeout"),
    );
    assert_eq!(other.exit_code, crate::core::error::EXIT_FAILED);
    assert!(other.message.contains("timeout"));
}

#[test]
fn a_deploy_keeps_the_dns_mode_and_root_domain_of_the_deployment_unless_given() {
    let labeled = || Some("pelle.io".to_string());
    // a deploy without them keeps what the deployment carries: no switch to subdomain
    assert_eq!(
        resolve_dns(
            "beta",
            Intent::Deploy,
            None,
            None,
            Some(DnsMode::Domain),
            labeled()
        ),
        Ok((DnsMode::Domain, labeled()))
    );
    // given, they change it
    assert_eq!(
        resolve_dns(
            "beta",
            Intent::Deploy,
            Some(DnsMode::Subdomain),
            Some("example.com"),
            Some(DnsMode::Domain),
            labeled()
        ),
        Ok((DnsMode::Subdomain, Some("example.com".to_string())))
    );
    // a deployment from before the labels: subdomain, and the root domain must be given
    assert_eq!(
        resolve_dns("beta", Intent::Deploy, None, Some(" pelle.io "), None, None),
        Ok((DnsMode::Subdomain, labeled()))
    );
    assert_eq!(
        resolve_dns("beta", Intent::Deploy, None, None, None, None),
        Err(t!(
            "shared.deployments.deploy_without_root_domain",
            name = "beta"
        )
        .to_string())
    );
    assert!(resolve_dns("beta", Intent::Deploy, None, Some("  "), None, None).is_err());
}

#[test]
fn a_create_is_a_subdomain_one_unless_given_and_a_destroy_needs_neither() {
    assert_eq!(
        resolve_dns("alpha", Intent::Create, None, Some("pelle.io"), None, None),
        Ok((DnsMode::Subdomain, Some("pelle.io".to_string())))
    );
    assert_eq!(
        resolve_dns(
            "alpha",
            Intent::Create,
            Some(DnsMode::Domain),
            None,
            None,
            None
        ),
        Ok((DnsMode::Domain, None))
    );
    assert_eq!(
        resolve_dns(
            "alpha",
            Intent::Destroy,
            None,
            None,
            Some(DnsMode::Domain),
            None
        ),
        Ok((DnsMode::Subdomain, None))
    );
}

#[test]
fn the_server_type_follows_the_location() {
    assert_eq!(server_type_for(Location::hel1), ServerType::cx23);
    assert_eq!(server_type_for(Location::sin), ServerType::cpx21);
    assert_eq!(LOCATIONS[0].id, Location::nbg1);
}

/// a project where only the data volume of `alpha` is left, of `size` GB, and nothing else
async fn only_the_volume(spec: &DeploymentSpec, size: u64) -> wiremock::MockServer {
    use wiremock::matchers::{method, path, query_param};
    use wiremock::{Mock, ResponseTemplate};
    let server = wiremock::MockServer::start().await;
    let mut labels = HetznerLabels::new();
    labels.add_all(&spec.identity()).unwrap();
    let volume = serde_json::json!({
        "id": 3, "name": spec.data_volume().name, "format": "ext4",
        "linux_device": "/dev/disk/by-id/scsi-0HC_Volume_3", "size": size,
        "protection": { "delete": true }, "server": null, "status": "available",
        "location": { "id": 2, "name": "nbg1" }, "labels": labels.into_raw()
    });
    Mock::given(method("GET"))
        .and(path("/volumes"))
        .and(query_param("name", spec.data_volume().name))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "volumes": [volume],
            "meta": { "pagination": { "page": 1, "per_page": 50, "next_page": null, "total_entries": 1 } }
        })))
        .mount(&server)
        .await;
    for kind in ["ssh_keys", "volumes", "primary_ips", "firewalls", "servers"] {
        Mock::given(method("GET"))
            .and(path(format!("/{kind}")))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                kind: [],
                "meta": { "pagination": { "page": 1, "per_page": 50, "next_page": null, "total_entries": 0 } }
            })))
            .mount(&server)
            .await;
    }
    server
}

#[tokio::test]
async fn a_destroy_plans_without_refusing_a_volume_smaller_than_today() {
    // the volume was made 16 GB; a later sakctl declares 32
    let mut spec = DeploymentSpec::new(
        "alpha",
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE+u0dEVRZDzzp4E1teCqF49r8ig3YEk8eaPqNWfDcPb alpha",
        Location::nbg1,
        ServerType::cx23,
    )
    .unwrap();
    spec.data_volume_gb = 32;
    let server = only_the_volume(&spec, 16).await;
    let api = HetznerApi::with_base_url("token", &server.uri()).with_retry(
        sak_cloud::hetzner::api::Backoff::new(
            1,
            std::time::Duration::ZERO,
            std::time::Duration::ZERO,
        ),
    );
    let key = crate::core::keys::derive_key("alpha", "a password of sixteen").unwrap();
    let prepared = Prepared {
        provider: ProviderType::Hetzner,
        runtime: HetznerProviderRuntime::from_api(Arc::new(api), Location::nbg1, ServerType::cx23),
        spec,
        stale_records: Vec::new(),
        client_key: key.clone(),
        host_key: key,
        api_public_key: String::new(),
        admin_email: None,
        admin_password_hash: String::new(),
        smtp: None,
        acme_ca: AcmeCa::default(),
    };
    let roles = |run| AnsibleRolesProvisioner {
        run,
        runtime: ContainerRuntime::default(),
        force: false,
        server_changed: Default::default(),
        events: None,
        failure: Default::default(),
    };

    // a deploy must not create the volume anew: refused
    let mut deploy = prepared.registry(Intent::Deploy, roles).unwrap();
    assert!(deploy.diff_all().await.is_err());
    // a destroy creates nothing: its plan goes through, the volume still shows
    let mut destroy = prepared.registry(Intent::Destroy, roles).unwrap();
    let diffs = destroy.diff_all().await.unwrap();
    assert!(
        diffs
            .iter()
            .any(|diff| diff.resource.name == "sak-alpha-data"
                && diff.status == DiffStatus::HasChanges),
        "{diffs:?}"
    );
}
