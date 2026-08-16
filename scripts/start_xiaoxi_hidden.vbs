' 小汐后端自启器 — 登录时静默拉起（若已在运行则不重复启动）
Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

backendDir = "E:\小汐sonetto\backend"
pythonw = backendDir & "\.venv\Scripts\pythonw.exe"

' 已有 python/pythonw 在跑 main.py 就不动（用 WMIC 查命令行；查不到也放行）
On Error Resume Next
Set wmi = GetObject("winmgmts:\\.\root\cimv2")
Set procs = wmi.ExecQuery("SELECT CommandLine FROM Win32_Process WHERE Name LIKE 'python%'")
already = False
For Each p In procs
    If InStr(p.CommandLine & "", "main.py") > 0 Then already = True
Next
On Error GoTo 0

If Not already Then
    shell.CurrentDirectory = backendDir
    shell.Environment("PROCESS").Item("PYTHONIOENCODING") = "utf-8"
    shell.Run """" & pythonw & """ main.py", 0, False
End If
