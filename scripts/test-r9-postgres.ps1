param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$container = 'sns-r9-test-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$savedPort = $env:R9_TEST_PG_PORT
$savedJava = $env:JAVA_HOME
function Invoke-Docker {
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw "Docker failed: $($args[0])" }
}
Push-Location $repoRoot
try {
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    Invoke-Docker run -d --name $container -p '127.0.0.1::5432' -e POSTGRES_PASSWORD=r9-isolated-test -e POSTGRES_DB=r9validation postgres:16 | Out-Null
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        & docker exec $container pg_isready -U postgres -d r9validation 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'Disposable PostgreSQL did not become ready' }
    Invoke-Docker exec $container psql -v ON_ERROR_STOP=1 -U postgres -d r9validation -c 'CREATE SCHEMA auth; CREATE TABLE auth.users(id uuid PRIMARY KEY);'
    $migrations = Join-Path $repoRoot 'src/main/resources/db/migration'
    $flyway = @('run', '--rm', '--network', "container:$container", '--mount', "type=bind,source=$migrations,target=/flyway/sql,readonly",
        'flyway/flyway:13.12.1', '-url=jdbc:postgresql://127.0.0.1:5432/r9validation', '-user=postgres', '-password=r9-isolated-test')
    Invoke-Docker @flyway '-target=9' migrate
    # Existing V9 records must survive V10, with active subjects by default.
    @'
INSERT INTO auth.users VALUES ('11111111-1111-1111-1111-111111111111');
INSERT INTO app_user(id) SELECT id FROM auth.users;
INSERT INTO subject(id,user_id,name) OVERRIDING SYSTEM VALUE VALUES (900,'11111111-1111-1111-1111-111111111111','legacy');
INSERT INTO material(id,subject_id,file_url,file_type,kind,size,status) OVERRIDING SYSTEM VALUE VALUES (900,900,'legacy.pdf','PDF','LECTURE',100,'READY');
INSERT INTO category(id,subject_id,material_id,name,page_start,page_end,"order") OVERRIDING SYSTEM VALUE VALUES (900,900,900,'legacy',1,1,1);
INSERT INTO question(id,category_id,type,stem,explanation) OVERRIDING SYSTEM VALUE VALUES (900,900,'OX','legacy','explanation');
INSERT INTO session(id,user_id,mode,status) OVERRIDING SYSTEM VALUE VALUES (900,'11111111-1111-1111-1111-111111111111','REVIEW','ENDED');
INSERT INTO session_question VALUES (900,900,1);
INSERT INTO attempt(user_id,question_id,session_id,answer,correct) VALUES ('11111111-1111-1111-1111-111111111111',900,900,'X',false);
INSERT INTO wrong_note(user_id,question_id) VALUES ('11111111-1111-1111-1111-111111111111',900);
'@ | & docker exec -i $container psql -v ON_ERROR_STOP=1 -U postgres -d r9validation
    if ($LASTEXITCODE -ne 0) { throw 'Legacy fixture failed' }
    Invoke-Docker @flyway migrate
    Invoke-Docker @flyway validate
    @'
DO $$ BEGIN
 IF (SELECT count(*) FROM subject WHERE id=900 AND deleted_at IS NULL)<>1
 OR (SELECT count(*) FROM question WHERE id=900 AND explanation='explanation')<>1
 OR (SELECT count(*) FROM session WHERE id=900)<>1
 OR (SELECT count(*) FROM attempt WHERE question_id=900)<>1
 OR (SELECT count(*) FROM wrong_note WHERE question_id=900)<>1
 THEN RAISE EXCEPTION 'V10 lost legacy history'; END IF;
END $$;
'@ | & docker exec -i $container psql -v ON_ERROR_STOP=1 -U postgres -d r9validation
    if ($LASTEXITCODE -ne 0) { throw 'Legacy preservation verification failed' }
    $binding = Invoke-Docker port $container 5432
    if ($binding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Expected loopback-only test database' }
    $env:R9_TEST_PG_PORT = $Matches[1]
    & .\gradlew.bat postgresTest --no-daemon --max-workers=2
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL integration tests failed' }
} finally {
    # This name was generated above; never remove containers supplied by the caller.
    & docker rm -f -v $container | Out-Null
    $env:R9_TEST_PG_PORT = $savedPort
    $env:JAVA_HOME = $savedJava
    Pop-Location
}
