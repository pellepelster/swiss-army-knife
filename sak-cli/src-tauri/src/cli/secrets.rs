//! the secrets of the command line: the token, the password of a deployment and of the mail
//! server, each from the environment or typed without echo, never an argument

use super::*;

/// the token of the project: HCLOUD_TOKEN unless `ask`, else typed on the terminal without echo.
/// Never an argument, because every process of the machine can read the arguments. Without a token
/// and a terminal, a usage error
pub(super) fn hcloud_token(
    ask: bool,
    from_env: Option<String>,
) -> Result<HetznerToken, WizardError> {
    // a token copied with a line break or a space around it is the token
    if !ask && let Some(token) = from_env.filter(|token| !token.trim().is_empty()) {
        return Ok(HetznerToken(token.trim().to_string()));
    }
    if !std::io::stdin().is_terminal() {
        return Err(WizardError::new(t!("cli.no_hcloud_token"), EXIT_USAGE));
    }
    rpassword::prompt_password(t!("cli.hcloud_token_prompt").as_ref())
        .map(|token| HetznerToken(token.trim().to_string()))
        .map_err(|error| error.to_string().into())
}

/// how often a password is typed: twice for a new one, so a typo does not become the password
#[derive(Clone, Copy, PartialEq, Eq)]
pub(super) enum Typed {
    Once,
    Twice,
}

/// the password of the deployment and its repetition: SAK_PASSWORD unless `ask`, else typed on the
/// terminal without echo, two times for a create. Never an argument, because every process of the
/// machine can read the arguments. Without SAK_PASSWORD and a terminal, a usage error
pub(super) fn password(
    ask: bool,
    typed: Typed,
    from_env: Option<String>,
) -> Result<(String, String), WizardError> {
    if !ask && let Some(password) = from_env.filter(|password| !password.is_empty()) {
        return Ok((password.clone(), password));
    }
    if !std::io::stdin().is_terminal() {
        return Err(WizardError::new(t!("cli.no_password"), EXIT_USAGE));
    }
    let prompt = |prompt: std::borrow::Cow<'_, str>| {
        rpassword::prompt_password(prompt.as_ref()).map_err(|error| error.to_string())
    };
    let first = prompt(t!("cli.password_prompt"))?;
    let second = match typed {
        Typed::Twice => prompt(t!("cli.password_repeat_prompt"))?,
        Typed::Once => first.clone(),
    };
    Ok((first, second))
}

/// the password at the mail server: SAK_SMTP_PASSWORD unless `ask`, else typed on the terminal
/// without echo; empty when the variable is not set. Never an argument, because every process of
/// the machine can read the arguments
pub(super) fn smtp_password(ask: bool, from_env: Option<String>) -> Result<String, WizardError> {
    if !ask {
        return Ok(from_env.unwrap_or_default());
    }
    if !std::io::stdin().is_terminal() {
        return Err(WizardError::new(t!("cli.no_smtp_password"), EXIT_USAGE));
    }
    rpassword::prompt_password(t!("cli.smtp_password_prompt").as_ref())
        .map_err(|error| error.to_string().into())
}

/// SAK_SMTP_PASSWORD of the environment of the process
pub(super) fn smtp_password_variable() -> Option<String> {
    std::env::var(SMTP_PASSWORD_VARIABLE).ok()
}

/// SAK_PASSWORD of the environment of the process
pub(super) fn password_variable() -> Option<String> {
    std::env::var(PASSWORD_VARIABLE).ok()
}

/// the name of the deployment typed on the terminal; anything else changes nothing
pub(super) fn confirm_restore(name: &str, file: &std::path::Path) -> Result<(), WizardError> {
    use std::io::Write;
    if !std::io::stdin().is_terminal() {
        return Err(WizardError::new(
            t!("cli.restore_no_terminal", name = name),
            EXIT_USAGE,
        ));
    }
    eprint!(
        "{}",
        t!("cli.restore_confirm", name = name, path = file.display())
    );
    std::io::stderr()
        .flush()
        .map_err(|error| error.to_string())?;
    let mut typed = String::new();
    std::io::stdin()
        .read_line(&mut typed)
        .map_err(|error| error.to_string())?;
    if typed.trim() == name {
        Ok(())
    } else {
        Err(t!("cli.restore_not_confirmed").to_string().into())
    }
}
