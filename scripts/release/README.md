Pre-release Check Script
========================

This script checks for old deprecated code. Deprecated code is considered old, if the last commit in the file was made before the last major (x.0.0) release.

Usage
-----

```
./pre_release.py
```

Script is configurable with environment variables:

    * SOURCE_PATH - path to the Narayana source code. Default is ".".
    * ASK_USER - whether the user should be asked what to do in case of failures. Default is "True".
    * FAIL_DEPRECATION - whether check should fail in case there is old deprecated code available. Default is "False".
