#define _DARWIN_C_SOURCE

#include <ApplicationServices/ApplicationServices.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <mach-o/dyld.h>
#include <pwd.h>
#include <spawn.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

#ifndef CAMWORK_VERSION
#error "CAMWORK_VERSION должен быть задан при компиляции"
#endif

static const char *APP_NAME = "CamWork Translate";
static const char *ORIGINAL_LAUNCHER = "CamWork Translate.bin";

static void report_error(const char *message) {
    fprintf(stderr, "CamWork Translate: %s\n", message);
}

static bool join_path(char *destination, size_t capacity, const char *base, const char *suffix) {
    const int written = snprintf(destination, capacity, "%s/%s", base, suffix);
    return written >= 0 && (size_t)written < capacity;
}

static bool ensure_directory(const char *path) {
    char current[PATH_MAX];
    const size_t length = strlen(path);
    if (length == 0 || length >= sizeof(current)) {
        return false;
    }

    memcpy(current, path, length + 1);
    for (char *cursor = current + 1; *cursor != '\0'; ++cursor) {
        if (*cursor != '/') {
            continue;
        }
        *cursor = '\0';
        if (mkdir(current, 0755) != 0 && errno != EEXIST) {
            return false;
        }
        *cursor = '/';
    }

    return mkdir(current, 0755) == 0 || errno == EEXIST;
}

static const char *resolve_home_directory(void) {
    const char *home = getenv("HOME");
    if (home != NULL && home[0] != '\0') {
        return home;
    }

    const struct passwd *account = getpwuid(getuid());
    if (account != NULL && account->pw_dir != NULL && account->pw_dir[0] != '\0') {
        return account->pw_dir;
    }
    return NULL;
}

static bool copy_defaults(const char *source, const char *destination) {
    pid_t child = 0;
    char *const arguments[] = {
        "/usr/bin/ditto",
        (char *)source,
        (char *)destination,
        NULL
    };

    const int spawn_result = posix_spawn(
        &child,
        arguments[0],
        NULL,
        NULL,
        arguments,
        environ
    );
    if (spawn_result != 0) {
        return false;
    }

    int status = 0;
    if (waitpid(child, &status, 0) < 0) {
        return false;
    }
    return WIFEXITED(status) && WEXITSTATUS(status) == 0;
}

static bool write_marker(const char *path) {
    const int descriptor = open(path, O_CREAT | O_TRUNC | O_WRONLY, 0644);
    if (descriptor < 0) {
        return false;
    }

    const char content[] = CAMWORK_VERSION "\n";
    const ssize_t written = write(descriptor, content, sizeof(content) - 1);
    const int close_result = close(descriptor);
    return written == (ssize_t)(sizeof(content) - 1) && close_result == 0;
}

static char *make_app_data_option(const char *app_data) {
    if (strchr(app_data, '"') != NULL || strchr(app_data, '\n') != NULL) {
        return NULL;
    }

    const char prefix[] = "-DappData=\"";
    const size_t required = sizeof(prefix) - 1 + strlen(app_data) + 2;
    char *option = calloc(required, sizeof(char));
    if (option == NULL) {
        return NULL;
    }

    const int written = snprintf(option, required, "%s%s\"", prefix, app_data);
    if (written < 0 || (size_t)written >= required) {
        free(option);
        return NULL;
    }
    return option;
}

static bool append_java_tool_option(const char *option) {
    const char *existing = getenv("JAVA_TOOL_OPTIONS");
    if (existing == NULL || existing[0] == '\0') {
        return setenv("JAVA_TOOL_OPTIONS", option, 1) == 0;
    }

    const size_t required = strlen(existing) + 1 + strlen(option) + 1;
    char *combined = calloc(required, sizeof(char));
    if (combined == NULL) {
        return false;
    }

    const int written = snprintf(combined, required, "%s %s", existing, option);
    const bool success = written >= 0 && (size_t)written < required &&
        setenv("JAVA_TOOL_OPTIONS", combined, 1) == 0;
    free(combined);
    return success;
}

/*
 * Показывает штатное окно macOS с кнопкой «Открыть настройки», если «Универсальный доступ» ещё
 * не выдан. Без этого разрешения не работает ни перехват Shift, ни чтение выделенного текста, а
 * найти нужный переключатель самостоятельно — задача не для всякого пользователя.
 *
 * Выдать разрешение из программы нельзя; система показывает окно сама и только когда разрешения
 * действительно нет, поэтому вызов безвреден при каждом запуске.
 */
static void request_accessibility_permission(void) {
    const void *keys[] = { kAXTrustedCheckOptionPrompt };
    const void *values[] = { kCFBooleanTrue };
    CFDictionaryRef options = CFDictionaryCreate(
        kCFAllocatorDefault,
        keys,
        values,
        1,
        &kCFTypeDictionaryKeyCallBacks,
        &kCFTypeDictionaryValueCallBacks
    );
    if (options == NULL) {
        return;
    }

    AXIsProcessTrustedWithOptions(options);
    CFRelease(options);
}

int main(int argc, char **argv) {
    (void)argc;

    request_accessibility_permission();

    char raw_executable[PATH_MAX];
    uint32_t raw_size = (uint32_t)sizeof(raw_executable);
    if (_NSGetExecutablePath(raw_executable, &raw_size) != 0) {
        report_error("не удалось определить путь запуска");
        return 70;
    }

    char executable[PATH_MAX];
    if (realpath(raw_executable, executable) == NULL) {
        report_error("не удалось разрешить путь запуска");
        return 70;
    }

    char *separator = strrchr(executable, '/');
    if (separator == NULL) {
        report_error("не удалось определить каталог приложения");
        return 70;
    }
    *separator = '\0';
    const char *macos_directory = executable;

    char original_launcher[PATH_MAX];
    char bundled_defaults[PATH_MAX];
    // Ресурсы лежат в Contents/Resources, а не в Contents/app: всё, что jpackage кладёт рядом с
    // основным JAR, попадает в app.classpath, и тогда plugin.json первого плагина начинает
    // отвечать за все остальные.
    if (!join_path(original_launcher, sizeof(original_launcher), macos_directory, ORIGINAL_LAUNCHER) ||
        !join_path(bundled_defaults, sizeof(bundled_defaults), macos_directory, "../Resources/default-data")) {
        report_error("путь приложения слишком длинный");
        return 70;
    }

    const char *home = resolve_home_directory();
    if (home == NULL) {
        report_error("не удалось определить домашний каталог пользователя");
        return 71;
    }

    char application_support[PATH_MAX];
    char app_data[PATH_MAX];
    char marker_name[128];
    char marker[PATH_MAX];
    const int marker_name_length = snprintf(
        marker_name,
        sizeof(marker_name),
        ".bundled-content-%s",
        CAMWORK_VERSION
    );
    if (!join_path(application_support, sizeof(application_support), home, "Library/Application Support") ||
        !join_path(app_data, sizeof(app_data), application_support, APP_NAME) ||
        marker_name_length < 0 || (size_t)marker_name_length >= sizeof(marker_name) ||
        !join_path(marker, sizeof(marker), app_data, marker_name)) {
        report_error("путь каталога данных слишком длинный");
        return 71;
    }

    if (!ensure_directory(app_data)) {
        report_error("не удалось создать каталог данных пользователя");
        return 73;
    }

    if (access(marker, F_OK) != 0) {
        if (!copy_defaults(bundled_defaults, app_data)) {
            report_error("не удалось установить штатные плагины и ресурсы");
            return 74;
        }
        if (!write_marker(marker)) {
            report_error("не удалось зафиксировать версию штатных ресурсов");
            return 74;
        }
    }

    char *app_data_option = make_app_data_option(app_data);
    if (app_data_option == NULL || !append_java_tool_option(app_data_option)) {
        free(app_data_option);
        report_error("не удалось настроить каталог данных JVM");
        return 70;
    }
    free(app_data_option);

    argv[0] = original_launcher;
    execv(original_launcher, argv);

    report_error("не удалось запустить встроенное приложение");
    return 70;
}
