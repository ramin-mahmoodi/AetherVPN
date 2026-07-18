#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <android/log.h>
#include <string>
#include <vector>

#define LOG_TAG "AetherVPN_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jintArray JNICALL
Java_com_cluvexstudio_aether_AetherVpnService_startTun2SocksNative(JNIEnv *env, jobject thiz, jstring path, jint tun_fd, jstring config_path) {
    const char *nativePath = env->GetStringUTFChars(path, nullptr);
    const char *nativeConfig = env->GetStringUTFChars(config_path, nullptr);
    
    // Clear FD_CLOEXEC flag so the child process inherits the TUN file descriptor
    int flags = fcntl(tun_fd, F_GETFD);
    if (flags != -1) {
        fcntl(tun_fd, F_SETFD, flags & ~FD_CLOEXEC);
        LOGI("Cleared FD_CLOEXEC on fd %d", tun_fd);
    }

    // Create a pipe for stdout/stderr
    int pipefd[2];
    if (pipe(pipefd) != 0) {
        LOGE("Failed to create pipe");
        env->ReleaseStringUTFChars(path, nativePath);
        env->ReleaseStringUTFChars(config_path, nativeConfig);
        return nullptr;
    }

    pid_t pid = fork();
    if (pid == 0) {
        // Child process
        close(pipefd[0]); // Close read end
        dup2(pipefd[1], STDOUT_FILENO); // Redirect stdout to pipe
        dup2(pipefd[1], STDERR_FILENO); // Redirect stderr to pipe
        close(pipefd[1]);
        
        char *argv[] = {
            const_cast<char*>(nativePath),
            const_cast<char*>("-c"),
            const_cast<char*>(nativeConfig),
            nullptr
        };

        execv(nativePath, argv);
        
        // Should not reach here unless execv fails
        LOGE("execv failed!");
        _exit(1);
    }

    // Parent process
    close(pipefd[1]); // Close write end
    env->ReleaseStringUTFChars(path, nativePath);
    env->ReleaseStringUTFChars(config_path, nativeConfig);
    
    // Return [pid, pipe_read_fd]
    jintArray result = env->NewIntArray(2);
    jint fill[2];
    fill[0] = pid;
    fill[1] = pipefd[0];
    env->SetIntArrayRegion(result, 0, 2, fill);
    
    return result;
}
