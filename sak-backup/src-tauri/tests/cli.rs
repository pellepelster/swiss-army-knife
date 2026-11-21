//! the command line of sakbackup against a backend of this machine and its restic (TEST-2, an
//! integration test: it needs `./gradlew :sak-backend:installDist`, java and restic): a backup plan
//! of a folder into the first repository of the user (BACKUP-7) or a new one, its run, and the
//! folder restored where it was, after it was removed or changed, or into another folder

mod integration {
    use std::collections::BTreeMap;
    use std::io::Read;
    use std::net::TcpListener;
    use std::path::{Path, PathBuf};
    use std::process::{Child, Command, Output, Stdio};
    use std::time::{Duration, Instant};

    // the admin password of the backend and its argon2id hash (the one of local:backend:run)
    const ADMIN_PASSWORD: &str = "a password of sixteen";
    const ADMIN_PASSWORD_HASH: &str = "$argon2id$v=19$m=65536,t=3,p=1$al/AGZtQJD5CTnS21yvIXg$L7n0a0d/Gu4awCZm7tuz99SWhVvUedmj0UABpTBu6l4";
    const REPO_PASSWORD: &str = "the password of the repository";

    /// the backend in a process of its own, stopped when dropped
    struct Backend {
        child: Child,
        port: u16,
    }

    impl Drop for Backend {
        fn drop(&mut self) {
            let _ = self.child.kill();
            let _ = self.child.wait();
        }
    }

    fn free_port() -> u16 {
        TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port()
    }

    fn start_backend(work: &Path) -> Backend {
        let script = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../sak-backend/build/install/sak-backend/bin/sak-backend");
        assert!(
            script.is_file(),
            "{} is missing, run ./gradlew :sak-backend:installDist",
            script.display()
        );
        let port = free_port();
        let data = work.join("data");
        std::fs::create_dir_all(&data).unwrap();
        // single user: the password login only, no mail server; nothing listens for caddy, the
        // backend keeps trying in the background. A value starting with $ is a variable
        let config = format!(
            "ktor:\n  deployment:\n    port: {port}\nsak:\n  data_dir: $SAK_TEST_DATA_DIR\n  root_domain: example.org\n  dns_mode: domain\n  \
             caddy_admin_url: http://127.0.0.1:1\n  auth:\n    frontend_url: http://127.0.0.1:{port}\n    admins:\n      - admin@example.org\n    \
             admin_password: $SAK_TEST_ADMIN_PASSWORD_HASH\n"
        );
        let config_file = work.join("config.yaml");
        std::fs::write(&config_file, config).unwrap();
        let log = std::fs::File::create(work.join("backend.log")).unwrap();
        let child = Command::new(script)
            .arg(format!("-config={}", config_file.display()))
            .env("SAK_TEST_DATA_DIR", &data)
            .env("SAK_TEST_ADMIN_PASSWORD_HASH", ADMIN_PASSWORD_HASH)
            .stdout(Stdio::from(log.try_clone().unwrap()))
            .stderr(Stdio::from(log))
            .spawn()
            .expect("the backend starts");
        let backend = Backend { child, port };
        let started = Instant::now();
        while http("GET", port, "/api/v1/info", None, None).0 != 200 {
            assert!(
                started.elapsed() < Duration::from_secs(120),
                "the backend did not answer, see {}",
                work.join("backend.log").display()
            );
            std::thread::sleep(Duration::from_millis(500));
        }
        backend
    }

    /// one request with std only: its status and its body
    fn http(
        method: &str,
        port: u16,
        path: &str,
        body: Option<&str>,
        bearer: Option<&str>,
    ) -> (u16, String) {
        use std::io::Write;
        let Ok(mut stream) = std::net::TcpStream::connect(("127.0.0.1", port)) else {
            return (0, String::new());
        };
        let body = body.unwrap_or("");
        let auth = bearer
            .map(|token| format!("Authorization: Bearer {token}\r\n"))
            .unwrap_or_default();
        write!(
            stream,
            "{method} {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\nContent-Type: application/json\r\n{auth}Content-Length: {}\r\n\r\n{body}",
            body.len()
        )
        .unwrap();
        let mut answer = String::new();
        let _ = stream.read_to_string(&mut answer);
        let status = answer
            .split(' ')
            .nth(1)
            .and_then(|code| code.parse().ok())
            .unwrap_or(0);
        let content = answer
            .split_once("\r\n\r\n")
            .map(|(_, content)| content.to_string())
            .unwrap_or_default();
        (status, content)
    }

    /// a session token of the admin, by the password login
    fn sign_in(port: u16) -> String {
        let (status, body) = http(
            "POST",
            port,
            "/api/v1/auth/login/password",
            Some(&format!(r#"{{"password":"{ADMIN_PASSWORD}"}}"#)),
            None,
        );
        assert_eq!(status, 200, "{body}");
        let value: serde_json::Value = serde_json::from_str(&body).unwrap();
        value["sessionToken"].as_str().unwrap().to_string()
    }

    /// sakbackup with the session and `password` of the repository in its environment, as it ended
    fn sakbackup_output(
        work: &Path,
        port: u16,
        token: &str,
        password: &str,
        args: &[&str],
    ) -> Output {
        Command::new(env!("CARGO_BIN_EXE_sakbackup"))
            .args(args)
            .env_remove("SAK_BACKUP_ADDRESS")
            .env_remove("SAK_BACKUP_SESSION_TOKEN")
            .env_remove("SAK_BACKUP_REPO_PASSWORD")
            .env("SAK_BACKUP_ADDRESS", format!("http://127.0.0.1:{port}"))
            .env("SAK_BACKUP_SESSION_TOKEN", token)
            .env("SAK_BACKUP_REPO_PASSWORD", password)
            .env("HOME", work)
            .env("XDG_CONFIG_HOME", work.join("config"))
            .env("XDG_CACHE_HOME", work.join("cache"))
            .stdin(Stdio::null())
            .output()
            .unwrap()
    }

    /// sakbackup with the session and the password of the repository in its environment
    fn sakbackup(work: &Path, port: u16, token: &str, args: &[&str]) -> Output {
        let output = sakbackup_output(work, port, token, REPO_PASSWORD, args);
        assert!(
            output.status.success(),
            "sakbackup {} failed: {}{}",
            args.join(" "),
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        output
    }

    /// a backend of its own with a signed in user, in a directory of the test that goes at its end
    struct Instance {
        work: PathBuf,
        backend: Option<Backend>,
        token: String,
    }

    impl Instance {
        fn start(test: &str) -> Instance {
            let work =
                std::env::temp_dir().join(format!("sakbackup-cli-{test}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&work);
            std::fs::create_dir_all(&work).unwrap();
            let backend = start_backend(&work);
            let token = sign_in(backend.port);
            Instance {
                work,
                backend: Some(backend),
                token,
            }
        }

        /// sakbackup with this instance, its standard output; it fails the test when it fails
        fn run(&self, args: &[&str]) -> String {
            let port = self.backend.as_ref().unwrap().port;
            String::from_utf8_lossy(&sakbackup(&self.work, port, &self.token, args).stdout)
                .into_owned()
        }

        /// sakbackup with this instance and `password` that must fail: its standard error; it ends
        /// with 1
        fn fail_with(&self, password: &str, args: &[&str]) -> String {
            let port = self.backend.as_ref().unwrap().port;
            let output = sakbackup_output(&self.work, port, &self.token, password, args);
            assert_eq!(
                output.status.code(),
                Some(1),
                "sakbackup {} did not fail: {}",
                args.join(" "),
                String::from_utf8_lossy(&output.stdout)
            );
            String::from_utf8_lossy(&output.stderr).into_owned()
        }

        /// sakbackup with this instance that must fail: its standard error
        fn fail(&self, args: &[&str]) -> String {
            self.fail_with(REPO_PASSWORD, args)
        }

        /// the directories of the repositories of restic on the data volume of the instance
        fn restic_dirs(&self) -> Vec<PathBuf> {
            let mut dirs: Vec<PathBuf> = std::fs::read_dir(self.work.join("data/restic"))
                .map(|entries| {
                    entries
                        .map(|entry| entry.unwrap().path())
                        .filter(|path| !path.ends_with(".tmp"))
                        .collect()
                })
                .unwrap_or_default();
            dirs.sort();
            dirs
        }

        /// the repositories and their sizes, as `repo list` shows them
        fn repositories(&self) -> BTreeMap<String, u64> {
            self.run(&["repo", "list"])
                .lines()
                .map(|line| {
                    let mut columns = line.split('\t');
                    let name = columns.next().unwrap().to_string();
                    (name, columns.next().unwrap().parse().unwrap())
                })
                .collect()
        }

        /// a backup plan of the folder into the repository, and its first run
        fn back_up(&self, plan: &str, repository: &str, folder: &Path) {
            self.run(&[
                "plan",
                "create",
                plan,
                "--repo",
                repository,
                folder.to_str().unwrap(),
            ]);
            let listed = self.run(&["plan", "list"]);
            assert!(
                listed.contains(plan) && listed.contains(folder.to_str().unwrap()),
                "{listed}"
            );
            // the first run initializes the repository with the password of the variable, when asked
            // to (BK-6)
            self.run(&["run", plan, "--init"]);
        }
    }

    impl Drop for Instance {
        fn drop(&mut self) {
            drop(self.backend.take());
            if !std::thread::panicking() {
                let _ = std::fs::remove_dir_all(&self.work);
            }
        }
    }

    /// a folder with a file of random content below a folder of its own and a text file: the
    /// contents by their paths in the folder
    fn folder(dir: &Path) -> BTreeMap<PathBuf, Vec<u8>> {
        std::fs::create_dir_all(dir.join("nested")).unwrap();
        let mut random = vec![0u8; 1024 * 1024];
        getrandom::fill(&mut random).unwrap();
        let files = BTreeMap::from([
            (PathBuf::from("nested/random.bin"), random),
            (PathBuf::from("letter.txt"), b"Dear restic".to_vec()),
        ]);
        for (path, content) in &files {
            std::fs::write(dir.join(path), content).unwrap();
        }
        files
    }

    /// the files of `dir` have the contents of `files`
    fn assert_same(files: &BTreeMap<PathBuf, Vec<u8>>, dir: &Path) {
        for (path, content) in files {
            let restored = std::fs::read(dir.join(path))
                .unwrap_or_else(|error| panic!("{}: {error}", dir.join(path).display()));
            assert!(restored == *content, "{} differs", path.display());
        }
    }

    /// the size of the files, to compare with the size of a repository
    fn size(files: &BTreeMap<PathBuf, Vec<u8>>) -> u64 {
        files.values().map(|content| content.len() as u64).sum()
    }

    #[test]
    fn a_folder_is_backed_up_into_the_first_repository_and_restored_after_it_was_removed() {
        let instance = Instance::start("first");
        // the sign-in made the first repository (BACKUP-7)
        assert_eq!(
            instance.repositories().into_keys().collect::<Vec<_>>(),
            ["my-backups"]
        );
        let documents = instance.work.join("documents");
        let files = folder(&documents);

        instance.back_up("documents", "my-backups", &documents);
        std::fs::remove_dir_all(&documents).unwrap();
        instance.run(&["restore", "documents"]);
        assert_same(&files, &documents);
        // the repository holds the backup on the instance, encrypted
        assert!(instance.repositories()["my-backups"] > size(&files));
    }

    #[test]
    fn a_folder_is_backed_up_into_a_new_repository_and_restored_after_it_was_removed() {
        let instance = Instance::start("new");
        instance.run(&["repo", "create", "laptop"]);
        assert_eq!(
            instance.repositories().into_keys().collect::<Vec<_>>(),
            ["laptop", "my-backups"]
        );
        let documents = instance.work.join("documents");
        let files = folder(&documents);

        instance.back_up("documents", "laptop", &documents);
        std::fs::remove_dir_all(&documents).unwrap();
        instance.run(&["restore", "documents"]);
        assert_same(&files, &documents);
        // the backup went into the repository of the plan only
        let sizes = instance.repositories();
        assert!(sizes["laptop"] > size(&files), "{sizes:?}");
        assert_eq!(sizes["my-backups"], 0, "{sizes:?}");
    }

    #[test]
    fn a_folder_is_restored_into_another_folder_below_its_path() {
        let instance = Instance::start("target");
        let documents = instance.work.join("documents");
        let files = folder(&documents);
        instance.back_up("documents", "my-backups", &documents);
        let before = std::fs::read_dir(&documents).unwrap().count();

        let elsewhere = instance.work.join("elsewhere");
        instance.run(&[
            "restore",
            "documents",
            "--target",
            elsewhere.to_str().unwrap(),
        ]);
        // restic keeps the whole path of the folder below the target
        assert_same(
            &files,
            &elsewhere.join(documents.strip_prefix("/").unwrap()),
        );
        // the folder itself is as it was
        assert_same(&files, &documents);
        assert_eq!(std::fs::read_dir(&documents).unwrap().count(), before);
    }

    #[test]
    fn a_restore_brings_back_a_missing_file_and_a_changed_one_and_keeps_a_new_one() {
        let instance = Instance::start("changed");
        let documents = instance.work.join("documents");
        let files = folder(&documents);
        instance.back_up("documents", "my-backups", &documents);

        std::fs::remove_file(documents.join("nested/random.bin")).unwrap();
        std::fs::write(documents.join("letter.txt"), "Dear restic, changed").unwrap();
        std::fs::write(documents.join("added.txt"), "after the backup").unwrap();
        instance.run(&["restore", "documents"]);
        assert_same(&files, &documents);
        // restic leaves a file the snapshot does not have
        assert_eq!(
            std::fs::read_to_string(documents.join("added.txt")).unwrap(),
            "after the backup"
        );
    }

    #[test]
    fn a_snapshot_is_listed_and_deleted_the_other_one_stays() {
        let instance = Instance::start("snapshots");
        let documents = instance.work.join("documents");
        folder(&documents);
        instance.back_up("documents", "my-backups", &documents);
        std::fs::write(documents.join("letter.txt"), "Dear restic, again").unwrap();
        instance.run(&["run", "documents"]);

        // the newest first: an id, the time, the computer and the paths
        let ids = |listed: &str| -> Vec<String> {
            listed
                .lines()
                .map(|line| line.split('\t').next().unwrap().to_string())
                .collect()
        };
        let listed = instance.run(&["snapshot", "list", "--repo", "my-backups"]);
        assert!(listed.contains(documents.to_str().unwrap()), "{listed}");
        let before = ids(&listed);
        assert_eq!(before.len(), 2, "{listed}");

        instance.run(&["snapshot", "delete", &before[1], "--repo", "my-backups"]);
        let after = ids(&instance.run(&["snapshot", "list", "--repo", "my-backups"]));
        assert_eq!(after, [before[0].clone()]);
        // the newest is what a restore brings back
        std::fs::remove_dir_all(&documents).unwrap();
        instance.run(&["restore", "documents"]);
        assert_eq!(
            std::fs::read_to_string(documents.join("letter.txt")).unwrap(),
            "Dear restic, again"
        );
    }

    #[test]
    fn a_run_keeps_the_snapshots_its_plan_keeps_and_removes_the_older_ones() {
        let instance = Instance::start("keep");
        let documents = instance.work.join("documents");
        folder(&documents);
        let path = documents.to_str().unwrap();
        // without --keep-* a plan keeps 7 daily, 4 weekly and 12 monthly snapshots
        instance.run(&["plan", "create", "documents", "--repo", "my-backups", path]);
        instance.run(&["plan", "set", "documents", "--keep-last", "2"]);
        let snapshots = || {
            instance
                .run(&["snapshot", "list", "--repo", "my-backups"])
                .lines()
                .count()
        };
        for round in 1..=3 {
            std::fs::write(
                documents.join("letter.txt"),
                format!("Dear restic, {round}"),
            )
            .unwrap();
            let said = instance.run(&["run", "documents", "--init"]);
            assert_eq!(
                said.contains("Removed 1 older snapshots."),
                round == 3,
                "{said}"
            );
        }
        assert_eq!(snapshots(), 2);
        // the newest stays
        std::fs::remove_dir_all(&documents).unwrap();
        instance.run(&["restore", "documents"]);
        assert_eq!(
            std::fs::read_to_string(documents.join("letter.txt")).unwrap(),
            "Dear restic, 3"
        );
        // keeping all, a run removes none
        instance.run(&["plan", "set", "documents", "--keep-all"]);
        instance.run(&["run", "documents"]);
        assert_eq!(snapshots(), 3);
    }

    // BK-1, BK-2 of the review
    #[test]
    fn plans_that_share_a_folder_keep_and_restore_their_own_snapshots() {
        let instance = Instance::start("shared");
        let (documents, photos) = (
            instance.work.join("documents"),
            instance.work.join("photos"),
        );
        folder(&documents);
        folder(&photos);
        let (documents_path, photos_path) = (documents.to_str().unwrap(), photos.to_str().unwrap());
        instance.run(&[
            "plan",
            "create",
            "documents",
            "--repo",
            "my-backups",
            "--keep-last",
            "1",
            documents_path,
        ]);
        instance.run(&[
            "plan",
            "create",
            "everything",
            "--repo",
            "my-backups",
            "--keep-all",
            documents_path,
            photos_path,
        ]);
        for round in 1..=3 {
            std::fs::write(
                documents.join("letter.txt"),
                format!("Dear restic, {round}"),
            )
            .unwrap();
            instance.run(&["run", "documents", "--init"]);
            // newer than the one of documents, and with its folder
            std::fs::write(
                documents.join("letter.txt"),
                format!("Dear restic, {round} and more"),
            )
            .unwrap();
            instance.run(&["run", "everything"]);
        }

        // the paths are the last column: documents keeps its last one, everything all three
        let listed = instance.run(&["snapshot", "list", "--repo", "my-backups"]);
        let with_paths = |paths: &str| {
            listed
                .lines()
                .filter(|line| line.rsplit('\t').next() == Some(paths))
                .count()
        };
        assert_eq!(with_paths(documents_path), 1, "{listed}");
        assert_eq!(
            with_paths(&format!("{documents_path} {photos_path}")),
            3,
            "{listed}"
        );

        // a restore of documents takes its own snapshot, not the newer one of everything
        let elsewhere = instance.work.join("elsewhere");
        instance.run(&[
            "restore",
            "documents",
            "--target",
            elsewhere.to_str().unwrap(),
        ]);
        let below = |folder: &Path| elsewhere.join(folder.strip_prefix("/").unwrap());
        assert_eq!(
            std::fs::read_to_string(below(&documents).join("letter.txt")).unwrap(),
            "Dear restic, 3"
        );
        assert!(!below(&photos).exists());
    }

    // BK-6 of the review: a new repository only with --init; one with keys and data but no config is
    // never initialized over
    #[test]
    fn a_repository_is_initialized_only_when_asked_and_never_over_its_data() {
        let instance = Instance::start("init");
        let documents = instance.work.join("documents");
        folder(&documents);
        let path = documents.to_str().unwrap();
        instance.run(&["plan", "create", "documents", "--repo", "my-backups", path]);
        let said = instance.fail(&["run", "documents"]);
        assert!(said.contains("give --init"), "{said}");
        assert!(instance.restic_dirs().is_empty());
        instance.run(&["run", "documents", "--init"]);

        // the config is gone, e.g. a fault of the instance: no new repository over the old data
        let [repository] = instance.restic_dirs().try_into().unwrap();
        std::fs::remove_file(repository.join("config")).unwrap();
        let said = instance.fail(&["run", "documents", "--init"]);
        assert!(!said.is_empty());
        assert!(!repository.join("config").exists());
        assert!(std::fs::read_dir(repository.join("keys")).unwrap().count() > 0);
        assert!(
            std::fs::read_dir(repository.join("snapshots"))
                .unwrap()
                .count()
                > 0
        );
    }

    // BK-13 of the review: an empty folder, e.g. a disk that is not mounted, removes no older snapshot
    #[test]
    fn an_empty_snapshot_fails_the_run_and_removes_nothing() {
        let instance = Instance::start("empty");
        let documents = instance.work.join("documents");
        folder(&documents);
        let path = documents.to_str().unwrap();
        instance.run(&[
            "plan",
            "create",
            "documents",
            "--repo",
            "my-backups",
            "--keep-last",
            "1",
            path,
        ]);
        instance.run(&["run", "documents", "--init"]);

        std::fs::remove_dir_all(&documents).unwrap();
        std::fs::create_dir_all(&documents).unwrap();
        for _ in 0..2 {
            let said = instance.fail(&["run", "documents"]);
            assert!(said.contains("has no file"), "{said}");
        }
        // the newest first: the two empty ones and the one with data, which a keep of 1 would have removed
        let ids: Vec<String> = instance
            .run(&["snapshot", "list", "--repo", "my-backups"])
            .lines()
            .map(|line| line.split('\t').next().unwrap().to_string())
            .collect();
        assert_eq!(ids.len(), 3, "{ids:?}");
        for id in &ids[..2] {
            instance.run(&["snapshot", "delete", id, "--repo", "my-backups"]);
        }
        std::fs::remove_dir_all(&documents).unwrap();
        instance.run(&["restore", "documents"]);
        assert!(documents.join("letter.txt").is_file());
    }

    // the messages of the command line when it fails: each ends it with 1
    #[test]
    fn the_command_line_says_why_it_failed() {
        let instance = Instance::start("failures");
        let documents = instance.work.join("documents");
        folder(&documents);
        instance.back_up("documents", "my-backups", &documents);

        let said = instance.fail_with("not the password", &["run", "documents"]);
        assert!(
            said.contains("not the password of the repository"),
            "{said}"
        );
        // no password and no terminal to type it
        let said = instance.fail_with("", &["run", "documents"]);
        assert!(
            said.contains("password of the repository is needed"),
            "{said}"
        );
        let said = instance.fail(&["run", "nothing"]);
        assert!(said.contains("no backup plan nothing"), "{said}");
        let said = instance.fail(&["snapshot", "list", "--repo", "nothing"]);
        assert!(said.contains("no repository nothing"), "{said}");
        let said = instance.fail(&["snapshot", "delete", "deadbeef", "--repo", "my-backups"]);
        assert!(said.contains("no snapshot of this id"), "{said}");

        // a plan whose snapshot is not there yet
        instance.run(&[
            "plan",
            "create",
            "later",
            "--repo",
            "my-backups",
            documents.to_str().unwrap(),
        ]);
        let said = instance.fail(&[
            "restore",
            "later",
            "--target",
            instance.work.join("x").to_str().unwrap(),
        ]);
        assert!(said.contains("no snapshot yet"), "{said}");

        // a session the instance refuses
        let port = instance.backend.as_ref().unwrap().port;
        let output = sakbackup_output(
            &instance.work,
            port,
            "eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl",
            REPO_PASSWORD,
            &["repo", "list"],
        );
        assert_eq!(output.status.code(), Some(1));
        let said = String::from_utf8_lossy(&output.stderr);
        assert!(said.contains("The session expired"), "{said}");
    }

    // a path of the plan that is gone: the run backs up the others, the expiry and a restore go on
    #[test]
    fn a_plan_with_a_path_that_is_gone_backs_up_expires_and_restores_the_others() {
        let instance = Instance::start("gone");
        let (documents, music) = (instance.work.join("documents"), instance.work.join("music"));
        let files = folder(&documents);
        folder(&music);
        instance.run(&[
            "plan",
            "create",
            "both",
            "--repo",
            "my-backups",
            "--keep-last",
            "1",
            documents.to_str().unwrap(),
            music.to_str().unwrap(),
        ]);
        instance.run(&["run", "both", "--init"]);
        std::fs::remove_dir_all(&music).unwrap();
        let said = instance.run(&["run", "both"]);
        assert!(said.contains("Removed 1 older snapshots."), "{said}");
        assert_eq!(
            instance
                .run(&["snapshot", "list", "--repo", "my-backups"])
                .lines()
                .count(),
            1
        );
        std::fs::remove_dir_all(&documents).unwrap();
        instance.run(&["restore", "both"]);
        assert_same(&files, &documents);
        assert!(!music.exists());
    }

    // a restore never deletes and never writes over a read-only file; its target is absolute
    #[test]
    fn a_restore_keeps_what_it_does_not_know_and_reports_what_it_could_not_write() {
        let instance = Instance::start("keeps");
        let documents = instance.work.join("documents");
        folder(&documents);
        instance.back_up("documents", "my-backups", &documents);

        std::fs::create_dir_all(documents.join("new folder")).unwrap();
        std::fs::write(documents.join("new folder/new.txt"), "new").unwrap();
        std::fs::write(documents.join("letter.txt"), "changed").unwrap();
        let mut permissions = std::fs::metadata(documents.join("letter.txt"))
            .unwrap()
            .permissions();
        permissions.set_readonly(true);
        std::fs::set_permissions(documents.join("letter.txt"), permissions.clone()).unwrap();

        let said = instance.fail(&["restore", "documents"]);
        assert!(said.contains("letter.txt"), "{said}");
        assert!(said.contains("1 files could not be written"), "{said}");
        assert_eq!(
            std::fs::read_to_string(documents.join("new folder/new.txt")).unwrap(),
            "new"
        );
        assert_eq!(
            std::fs::read_to_string(documents.join("letter.txt")).unwrap(),
            "changed"
        );
        #[allow(clippy::permissions_set_readonly_false)]
        permissions.set_readonly(false);
        std::fs::set_permissions(documents.join("letter.txt"), permissions).unwrap();

        instance.fail(&["restore", "documents", "--target", "relative/folder"]);
        assert!(!instance.work.join("relative").exists());
        assert!(!std::env::current_dir().unwrap().join("relative").exists());
    }

    // a plan of another computer is shown, never run or restored here (BACKUP-5)
    #[test]
    fn a_plan_of_another_computer_does_not_run_here() {
        let instance = Instance::start("other");
        let port = instance.backend.as_ref().unwrap().port;
        let (status, body) = http(
            "GET",
            port,
            "/api/v1/restic/repositories",
            None,
            Some(&instance.token),
        );
        assert_eq!(status, 200, "{body}");
        let listed: serde_json::Value = serde_json::from_str(&body).unwrap();
        let repository = listed["items"][0]["id"].as_u64().unwrap();
        let (status, body) = http(
            "POST",
            port,
            "/api/v1/restic/plans",
            Some(&format!(
                r#"{{"name":"elsewhere","repositoryId":{repository},"host":"another-computer","paths":["/home/anna"]}}"#
            )),
            Some(&instance.token),
        );
        assert_eq!(status, 201, "{body}");
        let listed = instance.run(&["plan", "list"]);
        assert!(listed.contains("another-computer"), "{listed}");
        for args in [&["run", "elsewhere"][..], &["restore", "elsewhere"][..]] {
            let said = instance.fail(args);
            assert!(said.contains("another-computer"), "{said}");
        }
    }
}
