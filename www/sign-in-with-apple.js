exports.signin = function(arg0, success, error) {
  cordova.exec(success, error, "SignInWithApple", "signin", [arg0]);
};
