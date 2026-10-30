//! the arguments of the command line: the commands, their options and how a value is parsed

use super::*;

/// the exit codes at the end of the help; a test makes sure they are those of `core::error`
pub(super) const EXIT_CODES: &str = "Exit codes:
  0  The command did what it should.
  1  The command failed.
  2  The arguments are wrong, e.g. a name that is none.
  3  Domain mode asked, another deployment manages the root domain in domain mode already.
  4  The DNS mode asked is not the one other deployments use the root domain in: a root
     domain has one domain deployment or only subdomain deployments.
  5  A resource of a name the deployment uses belongs to someone else: it lacks the label
     sak.pelle.io/name or the password hash of the deployment. Nothing of it was changed.
  6  The data volume or a primary IP would have to be created anew, which would remove its
     data or address. Nothing was changed.
  7  update --check: a newer release of sakctl exists.
ssh ends with the exit code of the command on the server; when sakctl or ssh itself fails, 255.";

/// Swiss Army Knife: manage the deployments of a Hetzner Cloud project. Without a command the
/// window opens.
#[derive(Parser, Debug)]
#[command(
    name = "sakctl",
    version = crate::core::VERSION,
    disable_help_subcommand = true,
    after_help = EXIT_CODES
)]
pub struct Cli {
    #[command(subcommand)]
    pub command: Option<Command>,
}

#[derive(Subcommand, Debug, PartialEq, Eq)]
pub enum Command {
    /// the commands on the deployments of a project: each needs its token and the checks
    #[command(flatten)]
    Project(ProjectCommand),
    /// Update sakctl to the newest release from github.com
    ///
    /// Replaces sakctl only when the release is newer, after checking its sha-256.
    #[command(after_help = EXIT_CODES)]
    Update {
        /// Only check for a newer release, exit code 7 when there is one
        #[arg(long)]
        check: bool,
    },
}

#[derive(Subcommand, Debug, PartialEq, Eq)]
pub enum ProjectCommand {
    /// List the deployments and the state of their resources
    #[command(after_help = EXIT_CODES)]
    List(Provider),
    /// Create a deployment and provision its server
    ///
    /// Creates an ssh key, a data volume, a primary IPv4 and IPv6, a firewall, the server and its
    /// DNS records.
    #[command(after_help = EXIT_CODES)]
    Create {
        #[command(flatten)]
        provider: Provider,
        #[command(flatten)]
        deployment: DeploymentArgs,
    },
    /// Update a deployment and provision its server again
    ///
    /// Creates the server anew only when it must. The data volume and the addresses stay.
    #[command(after_help = EXIT_CODES)]
    Deploy {
        #[command(flatten)]
        provider: Provider,
        #[command(flatten)]
        deployment: DeploymentArgs,
    },
    /// Show the configuration of a running deployment
    #[command(subcommand)]
    Config(ConfigCommand),
    /// Open an ssh session on the server, or run a command after --
    ///
    /// Runs in the image of the tools with the key derived from the password, and checks the
    /// server's derived host key. The key is never written to disk.
    #[command(after_help = EXIT_CODES)]
    Ssh {
        #[command(flatten)]
        provider: Provider,
        /// The name of the deployment
        #[arg(env = NAME_VARIABLE)]
        name: String,
        /// Ask for the password, even when SAK_PASSWORD is set
        #[arg(long)]
        password: bool,
        /// A command to run on the server instead of a session
        #[arg(last = true)]
        command: Vec<String>,
    },
    /// Back up all data of a deployment
    ///
    /// The server compresses the data and encrypts it with a key derived from the password, to
    /// ~/.sak/backups/<name>/<name>-<UTC time>.age.sak.
    #[command(after_help = EXIT_CODES)]
    Backup {
        #[command(flatten)]
        provider: Provider,
        /// The name of the deployment
        #[arg(env = NAME_VARIABLE)]
        name: String,
        /// Ask for the password, even when SAK_PASSWORD is set
        #[arg(long)]
        password: bool,
    },
    /// Replace all data of a deployment with a backup
    ///
    /// The deployment must exist. The files of its provisioning stay.
    #[command(after_help = EXIT_CODES)]
    Restore {
        #[command(flatten)]
        provider: Provider,
        /// The name of the deployment
        #[arg(env = NAME_VARIABLE)]
        name: String,
        /// The backup, a file that `sakctl backup` wrote
        file: std::path::PathBuf,
        /// Ask for the password, even when SAK_PASSWORD is set
        #[arg(long)]
        password: bool,
        /// Replace the data without asking
        #[arg(long)]
        yes: bool,
    },
    /// Destroy a deployment, keeping its data volume
    ///
    /// Removes its DNS records, server, addresses, firewall and ssh key. --delete-data also
    /// deletes the data volume. A deployment that is already gone counts as destroyed.
    #[command(after_help = EXIT_CODES)]
    Destroy {
        #[command(flatten)]
        provider: Provider,
        #[command(flatten)]
        deployment: DeploymentArgs,
        /// Delete the data volume too, with everything stored on it
        #[arg(long)]
        delete_data: bool,
    },
    /// Maintain the server of a deployment: pending reboot, package updates, reboot
    #[command(subcommand)]
    Maintenance(MaintenanceCommand),
}

#[derive(Subcommand, Debug, PartialEq, Eq)]
pub enum MaintenanceCommand {
    /// Show whether a reboot is pending and what was upgraded since the boot
    #[command(after_help = EXIT_CODES)]
    Status(MaintenanceArgs),
    /// Upgrade all packages of the server
    ///
    /// Installs new dependencies, removes no package. Tells what changed and whether a reboot is
    /// pending.
    #[command(after_help = EXIT_CODES)]
    Update(MaintenanceArgs),
    /// Reboot the server, stopping the deployment first
    ///
    /// Stops the deployment in order, waits for a backup or update that runs, reboots the server
    /// and waits until the deployment answers again.
    #[command(after_help = EXIT_CODES)]
    Reboot {
        #[command(flatten)]
        args: MaintenanceArgs,
        /// Reboot only when a reboot is pending
        #[arg(long)]
        if_pending: bool,
        /// Do not wait until the deployment answers again
        #[arg(long)]
        no_wait: bool,
    },
}

#[derive(Args, Debug, PartialEq, Eq)]
pub struct MaintenanceArgs {
    #[command(flatten)]
    pub(super) provider: Provider,
    /// The name of the deployment
    #[arg(env = NAME_VARIABLE)]
    pub(super) name: String,
    /// Ask for the password, even when SAK_PASSWORD is set
    #[arg(long)]
    pub(super) password: bool,
    /// Print the answer of the server as JSON
    #[arg(long)]
    pub(super) json: bool,
}

/// what a command needs before it runs: the token of its provider, the password of the
/// deployment (asked again with `--password`, typed once or twice) and, for a create or deploy,
/// the password at the mail server (asked again with `--smtp-password`)
pub(super) struct Needs<'a> {
    pub(super) provider: &'a Provider,
    pub(super) password: Option<(bool, Typed)>,
    pub(super) smtp_password: Option<bool>,
}

impl ProjectCommand {
    pub(super) fn needs(&self) -> Needs<'_> {
        let needs = |provider, password, smtp_password| Needs {
            provider,
            password,
            smtp_password,
        };
        match self {
            ProjectCommand::List(provider) => needs(provider, None, None),
            ProjectCommand::Create {
                provider,
                deployment,
            } => needs(
                provider,
                Some((deployment.password, Typed::Twice)),
                Some(deployment.smtp_password),
            ),
            ProjectCommand::Deploy {
                provider,
                deployment,
            } => needs(
                provider,
                Some((deployment.password, Typed::Once)),
                Some(deployment.smtp_password),
            ),
            ProjectCommand::Destroy {
                provider,
                deployment,
                ..
            } => needs(provider, Some((deployment.password, Typed::Once)), None),
            ProjectCommand::Maintenance(maintenance) => {
                let args = maintenance.args();
                needs(&args.provider, Some((args.password, Typed::Once)), None)
            }
            ProjectCommand::Config(ConfigCommand::Show {
                provider, password, ..
            })
            | ProjectCommand::Ssh {
                provider, password, ..
            }
            | ProjectCommand::Backup {
                provider, password, ..
            }
            | ProjectCommand::Restore {
                provider, password, ..
            } => needs(provider, Some((*password, Typed::Once)), None),
        }
    }
}

impl MaintenanceCommand {
    pub(super) fn args(&self) -> &MaintenanceArgs {
        match self {
            MaintenanceCommand::Status(args)
            | MaintenanceCommand::Update(args)
            | MaintenanceCommand::Reboot { args, .. } => args,
        }
    }
}

#[derive(Subcommand, Debug, PartialEq, Eq)]
pub enum ConfigCommand {
    /// Show the configuration of a running deployment
    ///
    /// Reads it from the backend with a request signed with the key derived from the password.
    /// No secret is shown.
    #[command(after_help = EXIT_CODES)]
    Show {
        #[command(flatten)]
        provider: Provider,
        /// The name of the deployment
        #[arg(env = NAME_VARIABLE)]
        name: String,
        /// Ask for the password, even when SAK_PASSWORD is set
        #[arg(long)]
        password: bool,
    },
}

#[derive(Args, Debug, PartialEq, Eq)]
pub struct Provider {
    /// Ask for the Hetzner Cloud API token, even when HCLOUD_TOKEN is set
    ///
    /// The token is never an argument: it comes from HCLOUD_TOKEN or is typed without echo.
    #[arg(long)]
    pub(super) hcloud_token: bool,
}

/// the API token of the Hetzner Cloud project of the command. No Debug, the token never shows
pub struct HetznerToken(pub(super) String);

#[derive(Args, Debug, PartialEq, Eq)]
pub struct DeploymentArgs {
    /// The name of the deployment
    #[arg(env = NAME_VARIABLE)]
    pub(super) name: String,
    /// Ask for the password, even when SAK_PASSWORD is set
    ///
    /// The password has at least 16 characters, the keys of the deployment are derived from it.
    /// It is never an argument: it comes from SAK_PASSWORD or is typed without echo.
    #[arg(long)]
    pub(super) password: bool,
    /// The location of the server, its data volume and its addresses
    #[arg(long, env = LOCATION_VARIABLE, default_value = "nbg1", value_parser = parse_location)]
    pub(super) location: Location,
    /// The DNS mode: domain or subdomain
    ///
    /// domain manages <root domain> and *.<root domain>, subdomain manages <name>.<root domain>
    /// and *.<name>.<root domain>. Default: subdomain for a create, the deployment's own for a
    /// deploy.
    #[arg(long, value_parser = parse_dns_mode)]
    pub(super) dns_mode: Option<DnsMode>,
    /// The DNS zone of the deployment, e.g. pelle.io
    ///
    /// Needed for a create, a deploy takes the deployment's own. The zone must exist in the
    /// project, SAK does not create it.
    #[arg(long)]
    pub(super) root_domain: Option<String>,
    /// Who signs in: single or multi
    ///
    /// single: one user, the admin, signs in with the password of the deployment. multi: users
    /// sign in by e-mail, the admin also with the password; needs --admin-email and --smtp-host.
    #[arg(long, env = USER_MODE_VARIABLE, value_parser = parse_user_mode)]
    pub(super) user_mode: Option<UserMode>,
    /// The e-mail address of the admin (multi user)
    #[arg(long, env = ADMIN_EMAIL_VARIABLE)]
    pub(super) admin_email: Option<String>,
    /// The mail server for the login links (multi user)
    #[arg(long, env = SMTP_HOST_VARIABLE)]
    pub(super) smtp_host: Option<String>,
    /// The port of the mail server
    #[arg(long, env = SMTP_PORT_VARIABLE, default_value_t = DEFAULT_SMTP_PORT)]
    pub(super) smtp_port: u16,
    /// The user name at the mail server
    #[arg(long, env = SMTP_USERNAME_VARIABLE, default_value = "")]
    pub(super) smtp_username: String,
    /// The CA of the certificates: production or staging
    ///
    /// staging is the staging CA of Let's Encrypt for test deployments, sakctl then trusts its
    /// roots. Default: production for a create, the deployment's own for a deploy.
    #[arg(long, value_parser = parse_acme_ca)]
    pub(super) acme_ca: Option<AcmeCa>,
    /// Ask for the password at the mail server, even when SAK_SMTP_PASSWORD is set
    ///
    /// It is never an argument: it comes from SAK_SMTP_PASSWORD or is typed without echo. Without
    /// either, the mail server has none, and a deploy takes over the running one.
    #[arg(long)]
    pub(super) smtp_password: bool,
    /// Use STARTTLS with the mail server: true or false
    #[arg(long,
        action = clap::ArgAction::Set, env = SMTP_STARTTLS_VARIABLE, default_value_t = DEFAULT_SMTP_STARTTLS, value_parser = parse_flag)]
    pub(super) smtp_starttls: bool,
    /// Show the output of ansible while it provisions the server
    #[arg(long)]
    pub(super) show_log: bool,
    /// Apply a plan that destroys or creates resources anew without asking, e.g. in a script
    #[arg(long)]
    pub(super) yes: bool,
}

pub(super) fn parse_location(value: &str) -> Result<Location, String> {
    LOCATIONS
        .iter()
        .find(|location| key(&location.id) == value)
        .map(|location| location.id)
        .ok_or_else(|| {
            let known: Vec<String> = LOCATIONS.iter().map(|location| key(&location.id)).collect();
            format!("one of {}", known.join(", "))
        })
}

pub(super) fn parse_acme_ca(value: &str) -> Result<AcmeCa, String> {
    AcmeCa::parse(value).ok_or_else(|| {
        let known: Vec<&str> = AcmeCa::ALL.iter().map(|ca| ca.as_str()).collect();
        format!("one of {}", known.join(", "))
    })
}

pub(super) fn parse_user_mode(value: &str) -> Result<UserMode, String> {
    UserMode::parse(value).ok_or_else(|| {
        let known: Vec<&str> = UserMode::ALL.iter().map(|mode| mode.as_str()).collect();
        format!("one of {}", known.join(", "))
    })
}

pub(super) fn parse_dns_mode(value: &str) -> Result<DnsMode, String> {
    DnsMode::parse(value).ok_or_else(|| {
        let known: Vec<&str> = DnsMode::ALL.iter().map(|mode| mode.as_str()).collect();
        format!("one of {}", known.join(", "))
    })
}

impl HetznerToken {
    pub(super) fn tokens(&self) -> Vec<(ProviderType, String)> {
        vec![(ProviderType::Hetzner, self.0.clone())]
    }
}

impl DeploymentArgs {
    pub(super) fn deployment(
        &self,
        intent: Intent,
        (password, password_repeat): (String, String),
        smtp_password: &str,
    ) -> DeploymentRequest {
        DeploymentRequest {
            name: self.name.clone(),
            password,
            password_repeat,
            location: self.location,
            dns_mode: self.dns_mode,
            root_domain: self.root_domain.clone(),
            intent,
            // a destroy sets it, the only intent that has it
            delete_data: false,
            user_mode: self.user_mode,
            acme_ca: self.acme_ca,
            smtp: self.smtp_host.as_ref().map(|host| Smtp {
                host: host.clone(),
                port: self.smtp_port,
                username: self.smtp_username.clone(),
                password: smtp_password.to_string(),
                starttls: self.smtp_starttls,
            }),
            admin_email: self.admin_email.clone(),
            // the command line always runs the playbook of a deploy
            force: true,
        }
    }
}
