// Auto-login to Guacamole with user-mapping.xml credentials (user / empty password).
// Then redirect to the sole VNC connection "desktop".
(function() {
    if (sessionStorage.getItem("guac-auto-done")) return;
    sessionStorage.setItem("guac-auto-done", "1");

    var xhr = new XMLHttpRequest();
    xhr.open("POST", "api/tokens", true);
    xhr.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
    xhr.onload = function() {
        if (xhr.status === 200) {
            var authToken = JSON.parse(xhr.responseText).authToken;
            // "ZGVza3RvcABjAGRlZmF1bHQ=" = base64("desktop\0c\0default")
            window.location.hash = "#/client/ZGVza3RvcABjAGRlZmF1bHQ=?token=" + authToken;
            window.location.reload();
        }
    };
    xhr.send("username=user&password=");
})();
