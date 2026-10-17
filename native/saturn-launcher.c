#include <signal.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* Android daemon death/stop must also reap Tor's managed obfs4 subprocesses. */
static volatile sig_atomic_t stopping;
static void request_stop(int signal_number) { (void)signal_number; stopping = 1; }

int main(int argc, char **argv) {
    if (argc < 2) return 64;
    struct sigaction action = {0};
    action.sa_handler = request_stop;
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, 0);
    sigaction(SIGINT, &action, 0);
    pid_t parent = getppid();
    if (parent == 1 || prctl(PR_SET_PDEATHSIG, SIGTERM) != 0) return 65;
    if (getppid() != parent) return 66;
    pid_t child = fork();
    if (child < 0) return 67;
    if (child == 0) {
        setpgid(0, 0);
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() == 1) _exit(68);
        execv(argv[1], &argv[1]);
        _exit(69);
    }
    setpgid(child, child);
    const struct timespec pause = {0, 100000000};
    int status = 0;
    while (!stopping) {
        pid_t result = waitpid(child, &status, WNOHANG);
        if (result == child) {
            kill(-child, SIGKILL);
            return WIFEXITED(status) ? WEXITSTATUS(status) : 70;
        }
        if (result < 0) { kill(-child, SIGKILL); return 71; }
        nanosleep(&pause, 0);
    }
    kill(-child, SIGTERM);
    for (int attempt = 0; attempt < 20; attempt++) {
        if (waitpid(child, &status, WNOHANG) == child) { kill(-child, SIGKILL); return 0; }
        nanosleep(&pause, 0);
    }
    kill(-child, SIGKILL);
    waitpid(child, &status, 0);
    return 0;
}
