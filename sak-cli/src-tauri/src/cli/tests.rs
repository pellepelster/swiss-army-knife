//! the tests of the command line

use super::*;
use crate::core::error::{
    EXIT_DOMAIN_TAKEN, EXIT_FAILED, EXIT_MIXED_DNS_MODES, EXIT_NOT_OWNED, EXIT_RECREATE_REFUSED,
    EXIT_UPDATE_AVAILABLE, EXIT_USAGE,
};

fn parse(words: &[&str]) -> Result<Cli, clap::Error> {
    Cli::try_parse_from(std::iter::once("sakctl").chain(words.iter().copied()))
}

/// the command on a project the arguments ask for; none for nothing or `update`
fn project(cli: Cli) -> Option<ProjectCommand> {
    match cli.command {
        Some(Command::Project(command)) => Some(command),
        _ => None,
    }
}

#[test]
fn a_log_line_is_date_level_message_with_the_level_coloured() {
    let line = log_line(
        "2026-10-28 14:03:12",
        Level::Info,
        "Server 'alpha': created",
    );
    assert_eq!(
        anstream::adapter::strip_str(&line).to_string(),
        "2026-10-28 14:03:12 INFO Server 'alpha': created"
    );
    assert!(line.contains("\x1b[1m\x1b[32mINFO\x1b[0m Server"));
    let error = log_line("2026-10-28 14:03:12", Level::Error, "failed");
    assert_eq!(
        anstream::adapter::strip_str(&error).to_string(),
        "2026-10-28 14:03:12 ERROR failed"
    );
    assert!(error.contains("\x1b[31m"));
}

#[test]
fn the_help_lists_the_exit_codes_of_the_errors() {
    for code in [
        EXIT_OK,
        EXIT_FAILED,
        EXIT_USAGE,
        EXIT_DOMAIN_TAKEN,
        EXIT_MIXED_DNS_MODES,
        EXIT_NOT_OWNED,
        EXIT_RECREATE_REFUSED,
        EXIT_UPDATE_AVAILABLE,
    ] {
        assert!(EXIT_CODES.contains(&format!("\n  {code}  ")), "{code}");
    }
    for command in [
        &["--help"][..],
        &["create", "--help"],
        &["destroy", "--help"],
    ] {
        let help = parse(command).unwrap_err().to_string();
        assert!(help.contains("Exit codes:"), "{help}");
    }
    // clap ends a wrong argument with the code the help names
    assert_eq!(
        parse(&["create", "--moon"]).unwrap_err().exit_code(),
        EXIT_USAGE
    );
}

#[test]
fn maintenance_takes_the_name_and_its_options() {
    let cli = parse(&["maintenance", "reboot", "alpha", "--if-pending", "--json"]).unwrap();
    let Some(ProjectCommand::Maintenance(MaintenanceCommand::Reboot {
        args,
        if_pending,
        no_wait,
    })) = project(cli)
    else {
        panic!("maintenance reboot");
    };
    assert_eq!(
        (args.name.as_str(), args.json, if_pending, no_wait),
        ("alpha", true, true, false)
    );
    assert!(matches!(
        project(parse(&["maintenance", "status", "alpha"]).unwrap()),
        Some(ProjectCommand::Maintenance(MaintenanceCommand::Status(_)))
    ));
    assert!(parse(&["maintenance", "update", "alpha", "--if-pending"]).is_err());
    assert!(is_command_line(&["maintenance".to_string()]));
}

#[test]
fn yes_applies_a_plan_that_destroys_without_asking() {
    for command in ["create", "deploy", "destroy"] {
        let Some(
            ProjectCommand::Create { deployment, .. }
            | ProjectCommand::Deploy { deployment, .. }
            | ProjectCommand::Destroy { deployment, .. },
        ) = project(parse(&[command, "alpha", "--yes"]).unwrap())
        else {
            panic!("{command}");
        };
        assert!(deployment.yes, "{command}");
    }
}

#[test]
fn the_commands_are_told_from_the_window_a_word_is_the_command_line() {
    let args = |args: &[&str]| args.iter().map(|arg| arg.to_string()).collect::<Vec<_>>();
    for command_line in [
        &["list"][..],
        &["lst"],
        &["--help"],
        &["-V"],
        &["deploy", "alpha"],
    ] {
        assert!(is_command_line(&args(command_line)), "{command_line:?}");
    }
    for window in [&[][..], &["-psn_0_12345"], &["--some-desktop-flag"]] {
        assert!(!is_command_line(&args(window)), "{window:?}");
    }
    // a mistyped command is a usage error of clap, which names the command meant
    let error = parse(&["lst"]).err().unwrap().to_string();
    assert!(error.contains("list"), "{error}");
    assert_eq!(project(parse(&[]).unwrap()), None);
}

#[test]
fn config_show_takes_the_name_and_password_and_prints_no_secret() {
    let cli = parse(&["config", "show", "alpha", "--hcloud-token", "--password"]).unwrap();
    let Some(ProjectCommand::Config(ConfigCommand::Show { name, password, .. })) = project(cli)
    else {
        panic!("config show");
    };
    // --password asks for it, it takes no value
    assert_eq!((name.as_str(), password), ("alpha", true));
    assert!(
        parse(&[
            "config",
            "show",
            "alpha",
            "--hcloud-token",
            "--password",
            "p"
        ])
        .is_err()
    );
    assert!(is_command_line(&["config".to_string()]));

    use crate::core::deployments::{Detected, DetectedSmtp, SettingsSource, UserMode};
    let detected = Detected {
        source: Some(SettingsSource::Running),
        dns_mode: Some(sak_cloud::hetzner::deployment::DnsMode::Domain),
        root_domain: Some("pelle.io".to_string()),
        acme_ca: Some(sak_cloud::hetzner::deployment::AcmeCa::Production),
        user_mode: Some(UserMode::Multi),
        admin_email: Some("admin@example.org".to_string()),
        smtp: Some(DetectedSmtp {
            host: "smtp.example.org".to_string(),
            port: 465,
            username: String::new(),
            password: Some("the-smtp-password".to_string()),
            password_set: true,
            starttls: false,
        }),
        ..Detected::default()
    };
    let lines = config_lines(&detected);
    for line in [
        "dns_mode domain",
        "root_domain pelle.io",
        "user_mode multi",
        "admin_email admin@example.org",
        "smtp.host smtp.example.org",
        "smtp.username -",
        "smtp.password set",
    ] {
        assert!(lines.contains(&line.to_string()), "{line} in {lines:?}");
    }
    // single user: no admin, no mail server
    let single = Detected {
        user_mode: Some(UserMode::Single),
        ..detected.clone()
    };
    assert!(
        !config_lines(&single)
            .iter()
            .any(|line| line.starts_with("smtp.") || line.starts_with("admin_email"))
    );
    // where they come from, and why the deployment did not answer
    let local = Detected {
        source: Some(SettingsSource::Local),
        deployed_at: Some("2026-11-01T12:00:00+00:00".to_string()),
        unread: Some("no answer".to_string()),
        ..detected.clone()
    };
    let first = &shown_lines("alpha", &local)[0];
    assert!(
        first.contains("2026-11-01T12:00:00+00:00") && first.contains("no answer"),
        "{first}"
    );

    // the output has no password and no part of the api key
    let password = "a password of sixteen";
    let key = crate::core::keys::api_key("alpha", password).unwrap();
    let shown = shown_lines(" alpha ", &detected).join("\n");
    assert!(shown.contains("alpha"), "{shown}");
    assert!(!shown.contains(password) && !shown.contains(&key.public_key()));
    // nor the password of the mail server, which the backend answers for a redeploy
    assert!(!shown.contains("the-smtp-password"));
}

#[test]
fn ssh_takes_the_name_and_a_command_after_two_dashes() {
    assert!(is_command_line(&["ssh".to_string()]));
    let Some(ProjectCommand::Ssh {
        name,
        command,
        password,
        ..
    }) = project(parse(&["ssh", "alpha", "--", "uptime", "-p"]).unwrap())
    else {
        panic!("no ssh command");
    };
    assert_eq!(name, "alpha");
    assert_eq!(command, ["uptime", "-p"]);
    assert!(!password);
    let Some(ProjectCommand::Ssh { command, .. }) = project(parse(&["ssh", "alpha"]).unwrap())
    else {
        panic!("no ssh command");
    };
    assert!(command.is_empty());
}

#[test]
fn backup_takes_the_name_and_restore_the_name_and_the_file() {
    assert!(is_command_line(&["backup".to_string()]));
    assert!(is_command_line(&["restore".to_string()]));
    let Some(ProjectCommand::Backup { name, password, .. }) =
        project(parse(&["backup", "alpha"]).unwrap())
    else {
        panic!("no backup command");
    };
    assert_eq!((name.as_str(), password), ("alpha", false));
    let Some(ProjectCommand::Restore {
        name, file, yes, ..
    }) = project(
        parse(&[
            "restore",
            "alpha",
            "alpha-20261029T080000Z.age.sak",
            "--yes",
        ])
        .unwrap(),
    )
    else {
        panic!("no restore command");
    };
    assert_eq!(name, "alpha");
    assert_eq!(file, std::path::Path::new("alpha-20261029T080000Z.age.sak"));
    assert!(yes);
    // a restore needs its file
    assert!(parse(&["restore", "alpha"]).is_err());
}

#[test]
fn a_size_is_shown_in_the_largest_unit_below_1024() {
    assert_eq!(size(812), "812 B");
    assert_eq!(size(3 * 1024 * 1024 + 400 * 1024), "3.4 MiB");
    assert_eq!(size(2048), "2.0 KiB");
}

#[test]
fn the_hcloud_token_comes_from_hcloud_token_or_is_typed_never_from_an_argument() {
    assert_eq!(
        hcloud_token(false, Some("token".into())).unwrap().0,
        "token"
    );
    // the api gets the token without what was copied around it
    assert_eq!(
        hcloud_token(false, Some(" token\n".into())).unwrap().0,
        "token"
    );
    if !std::io::stdin().is_terminal() {
        for (ask, from_env) in [
            (true, Some("token".to_string())),
            (false, None),
            (false, Some(" ".to_string())),
        ] {
            assert_eq!(
                hcloud_token(ask, from_env)
                    .err()
                    .map(|error| error.exit_code),
                Some(EXIT_USAGE)
            );
        }
    }
    // --hcloud-token asks for it, it takes no value
    assert!(parse(&["list", "--hcloud-token", "the-token"]).is_err());
}

#[test]
fn the_smtp_password_comes_from_sak_smtp_password_or_is_typed_never_from_an_argument() {
    assert_eq!(
        smtp_password(false, Some("secret".into())).unwrap(),
        "secret"
    );
    // empty: a mail server without a login, or a redeploy takes over the running password
    assert_eq!(smtp_password(false, None).unwrap(), "");
    if !std::io::stdin().is_terminal() {
        assert_eq!(
            smtp_password(true, Some("secret".into()))
                .err()
                .map(|error| error.exit_code),
            Some(EXIT_USAGE)
        );
    }
    // --smtp-password asks for it, it takes no value
    assert!(
        parse(&[
            "create",
            "alpha",
            "--hcloud-token",
            "--smtp-password",
            "secret"
        ])
        .is_err()
    );
    assert!(parse(&["create", "alpha", "--hcloud-token", "--smtp-password"]).is_ok());
}

#[test]
fn the_password_comes_from_sak_password_or_is_typed_never_from_an_argument() {
    // SAK_PASSWORD, for both fields
    assert_eq!(
        password(false, Typed::Twice, Some("from the environment".into())).unwrap(),
        ("from the environment".into(), "from the environment".into())
    );
    // asked for, or no SAK_PASSWORD: typed, which needs a terminal; the tests have none
    if !std::io::stdin().is_terminal() {
        for (ask, from_env) in [
            (true, Some("x".to_string())),
            (false, None),
            (false, Some(String::new())),
        ] {
            assert_eq!(
                password(ask, Typed::Once, from_env).unwrap_err().exit_code,
                EXIT_USAGE
            );
        }
    }
}

#[test]
fn the_arguments_of_the_commands() {
    let cli = parse(&["list", "--hcloud-token"]).unwrap();
    assert_eq!(
        project(cli),
        Some(ProjectCommand::List(Provider { hcloud_token: true }))
    );

    let cli = parse(&["deploy", "alpha", "--hcloud-token", "--location", "nbg1"]).unwrap();
    let Some(ProjectCommand::Deploy {
        provider,
        deployment,
    }) = project(cli)
    else {
        panic!("a deploy");
    };
    assert!(provider.hcloud_token);
    assert_eq!(deployment.name, "alpha");
    assert_eq!(deployment.location, Location::nbg1);
    // not given: the core resolves them, a deploy to the deployment's own
    assert_eq!(deployment.dns_mode, None);
    assert_eq!(deployment.root_domain, None);
    assert!(!deployment.password);
    assert_eq!(
        deployment
            .deployment(Intent::Deploy, ("p".into(), "q".into()), "")
            .password_repeat,
        "q"
    );

    let cli = parse(&["destroy", "alpha", "--hcloud-token", "--password"]).unwrap();
    let Some(ProjectCommand::Destroy {
        deployment,
        delete_data,
        ..
    }) = project(cli)
    else {
        panic!("a destroy");
    };
    assert!(!delete_data);
    // without --yes a plan that destroys asks first
    assert!(!deployment.yes);
    assert_eq!(
        deployment
            .deployment(Intent::Destroy, Default::default(), "")
            .intent,
        Intent::Destroy
    );
    assert_eq!(deployment.location, Location::nbg1);
    assert!(!deployment.show_log);

    let cli = parse(&[
        "create",
        "alpha",
        "--hcloud-token",
        "--show-log",
        "--dns-mode",
        "domain",
        "--root-domain",
        "pelle.io",
        "--user-mode",
        "multi",
        "--smtp-host",
        "smtp.example.org",
        "--smtp-port",
        "465",
        "--smtp-starttls",
        "false",
    ])
    .unwrap();
    let Some(ProjectCommand::Create { deployment, .. }) = project(cli) else {
        panic!("a create");
    };
    assert!(deployment.show_log);
    assert_eq!(
        deployment
            .deployment(Intent::Create, Default::default(), "")
            .dns_mode,
        Some(DnsMode::Domain)
    );
    assert_eq!(
        deployment
            .deployment(Intent::Create, Default::default(), "")
            .root_domain
            .as_deref(),
        Some("pelle.io")
    );
    assert_eq!(
        deployment
            .deployment(Intent::Create, Default::default(), "")
            .user_mode,
        Some(UserMode::Multi)
    );
    let smtp = deployment
        .deployment(Intent::Create, Default::default(), "")
        .smtp
        .unwrap();
    assert_eq!(
        (smtp.host.as_str(), smtp.port, smtp.starttls),
        ("smtp.example.org", 465, false)
    );

    assert!(
        parse(&[
            "create",
            "alpha",
            "--hcloud-token",
            "--password",
            "--dns-mode",
            "whole"
        ])
        .is_err()
    );

    assert!(
        parse(&[
            "create",
            "alpha",
            "--hcloud-token",
            "--password",
            "--location",
            "moon"
        ])
        .is_err()
    );
}
