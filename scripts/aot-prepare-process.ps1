# Bind only this Prepare process and its future children to the launcher lifetime.
$launcherId = (Get-CimInstance Win32_Process -Filter "ProcessId=$PID").ParentProcessId
Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;

public static class AotPrepareProcess
{
    [StructLayout(LayoutKind.Sequential)]
    private struct BasicLimits
    {
        public long ProcessTime, JobTime;
        public uint Flags;
        public UIntPtr MinimumWorkingSet, MaximumWorkingSet;
        public uint ActiveProcesses;
        public UIntPtr Affinity;
        public uint Priority, Scheduling;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct IoCounters
    {
        public ulong ReadOperations, WriteOperations, OtherOperations, ReadBytes, WriteBytes, OtherBytes;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct ExtendedLimits
    {
        public BasicLimits Basic;
        public IoCounters Io;
        public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
    }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateJobObject(IntPtr attributes, string name);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetInformationJobObject(IntPtr job, int infoClass, ref ExtendedLimits info, uint size);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);

    public static void Start(int launcherId)
    {
        var launcher = Process.GetProcessById(launcherId);
        using (var self = Process.GetCurrentProcess())
        {
            // Capture the handle before waiting so PID reuse cannot change the owner.
            var launcherHandle = launcher.Handle;
            if (launcher.StartTime > self.StartTime || launcher.HasExited)
            {
                launcher.Dispose();
                throw new InvalidOperationException("AOT Prepare launcher has already exited.");
            }
            var job = CreateJobObject(IntPtr.Zero, null);
            if (job == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            var limits = new ExtendedLimits();
            limits.Basic.Flags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            if (!SetInformationJobObject(job, 9, ref limits, (uint)Marshal.SizeOf(limits)) ||
                !AssignProcessToJobObject(job, self.Handle))
            {
                int error = Marshal.GetLastWin32Error();
                CloseHandle(job);
                launcher.Dispose();
                throw new Win32Exception(error);
            }
            // The OS closes this non-inheritable handle on exit, including forced termination.
        }
        var watcher = new Thread(() => {
            launcher.WaitForExit();
            launcher.Dispose();
            try { Console.Error.WriteLine("AOT Prepare cancelled: launcher exited; stopping owned build processes."); }
            finally { Environment.Exit(130); }
        });
        watcher.IsBackground = true;
        watcher.Start();
    }
}
'@
[AotPrepareProcess]::Start($launcherId)
