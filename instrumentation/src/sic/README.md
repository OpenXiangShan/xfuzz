# Simulator Independent Coverage

## Notes

All files in this directory belong to the [ekiwi/simulator-independent-coverage](https://github.com/ekiwi/simulator-independent-coverage) repository.
The license is reserved by the original authors specified in the original repository.

Below is the original README in the original repository.

This repository contains code to reproduce results from our ASPLOS'23
paper on "Simulator Independent Coverage for RTL Hardware Languages".
Most results can be reproduced on a standard x86 Linux computer, however,
for the FireSim performance and area/frequency results a more complicated setup
on AWS cloud FPGAs is necessary.

_Hint_: The CI runs the equivalent of the reduced Kick the Tires tests. Feel free to have a look
at the [test.yml](.github/workflows/test.yml) in case you get stuck.

## Accessing the Artifact

Clone the repository to your local machine and define an environment variable that
points to the artifact root directory (this will be useful later):
```{.sh}
git clone https://github.com/ekiwi/simulator-independent-coverage.git
cd simulator-independent-coverage
git checkout asplos2023 # ensure you are on the correct branch
export ROOT=`pwd`
```


## Verilator Benchmarks (Figure 8 and Table 2)


### Install Verilator

**Kick the Tires**: Do all.

The measurements for the paper were taken with Verilator version `4.034`.
While our benchmarks should work with newer Verilator versions (at least in the `4.x` generation),
they will lead to different results since various optimizations have made their way into
Verilator since we conducted our experiments.

We provide the source code for Verilator `v4.034` as part of our artifact. More info can be found [in corresponding the Readme](ext/Readme.md). To build you need to first install all build requirements.
On Ubuntu this would be:

```{.sh}
sudo apt-get install -y git make autoconf g++ flex bison libfl2 libfl-dev bc
```

Now you can build a local copy of Verilator. The following assumes that
`$ROOT` points to the root of the artifact repository:

```{.sh}
cd ext/verilator-4.034-src/
autoconf
./configure --prefix=$ROOT/ext/verilator-4.034
make -j8 # adjust number according to the number of cores on your machine
make install
```

To be able to use this verilator version you need to add
`$ROOT/ext/verilator-4.034/bin` to your `PATH`. You might also need to set
the environment variable `VERILATOR_ROOT` to `$ROOT/ext/verilator-4.034`.
If you are using `bash` as your shell:
```{.sh}
export PATH=$PATH:$ROOT/ext/verilator-4.034/bin
export VERILATOR_ROOT=$ROOT/ext/verilator-4.034
```


When you now run `verilator --version` you should see the following output:

```{.sh}
Verilator 4.034 2020-05-03 rev UNKNOWN_REV
```

If you already have a different version of verilator installed on
your machine, please make sure to check the version every time before
you run an experiment.

### Install Hyperfine

**Kick the Tires**: Do all.

Please install `hyperfine` [following its documentation](https://github.com/sharkdp/hyperfine#installation).

### Install Java and sbt

To compile the FIRRTL passes that perform coverage instrumentation, you will need a JDK
and [sbt](https://www.scala-sbt.org/index.html).

On Ubuntu:

```{.sh}
sudo apt-get install default-jdk default-jre

echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list
echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | sudo tee /etc/apt/sources.list.d/sbt_old.list
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | sudo apt-key add
sudo apt-get update
sudo apt-get install sbt
```

### Table 2

**Kick the Tires**: run a shorter version of the script by adding `HYPERFINE_OPTS="--warmup=0 --runs=1"` to the `make` invocation

To re-create table 2, please run the following commands:

```{.sh}
verilator --version # make sure it is 4.034
cd $ROOT/benchmarks
make table2
cat build/table2.csv
```

Running the full version should take between 10 and 20min.

Please compare the data in the CSV file with table 2 in the paper.
Note that most numbers will be slightly off since our artifact is using
a newer version of the firrtl compiler which might include different
optimizations. The purpose of table 2 is to give the reader a feel for
the benchmarks used. Please make sure that we accurately did that.


### Figure 8

**Kick the Tires**: run a shorter version of the script by adding `HYPERFINE_OPTS="--warmup=0 --runs=1"` to the `make` invocation

To re-create figure 8, please run the following commands:

```{.sh}
verilator --version # make sure it is 4.034
cd $ROOT/benchmarks
make figure8
```

Running the full version should take between 1h and 2h.

```{.sh}
cat build/figure8_verilator_overhead_small.csv
cat build/figure8_verilator_overhead_large.csv
```

Please compare the data in the two CSV files with figure 8 in the paper.
The first file corresponds to the left half plot, and the second file to the right half plot.
Note that the CSV file contains percentage overhead in runtime when
adding the instrumentation.

Make sure that our main conclusion from Section 5.1 is supported by the numbers:
> We find that in general our instrumentation causes the same or slightly less overhead compared to Verilator's built-in coverage.

## Fuzzing (Figure 11)

### Install matplotlib and scipy

**Kick the Tires**: Do all.


On Ubuntu you can install them like this:

```{.sh}
sudo apt-get install -y python3-matplotlib python3-scipy
```

### Figure 11

**Kick the Tires**: run a shorter version of the script by adding `TIME_MIN=1 REPS=1` to the `make` invocation

Our artifact comes with a copy of the AFL source code which should be built automatically
by our Makefile:

```{.sh}
cd $ROOT/fuzzing
make figure11
```

This should take around `20min * 5 = 1h 40min` since we fuzz for 20 minutes and do
that 5 times in order to compute the average coverage over time.
Please open the `fuzzing.png` file and compare it to Figure 11. There will be some variation
since fuzzing is a stochastic process. Also note that Figure 11 cuts of the y-axis
bellow 70% while `fuzzing.png` shows the full y-axis.

## FireSim Integration

To reproduce our FireSim Integration results you need access to an AWS account.
While it is probably possible to reproduce our results using your [own on premise FPGAs](https://docs.fires.im/en/1.15.1/Advanced-Usage/Vitis.html)
instead of AWS, the on premise FPGA flow is **not officially supported** by this artifact.


### Basic AWS Setup


**Kick the Tires**: Try logging into the manager instance provided.

Please follow the following guides from the FireSim documentation:
- [1.1. First-time AWS User Setup](https://docs.fires.im/en/1.15.1/Initial-Setup/First-time-AWS-User-Setup.html)
- [1.2. Configuring Required Infrastructure in Your AWS Account](https://docs.fires.im/en/1.15.1/Initial-Setup/Configuring-Required-Infrastructure-in-Your-AWS-Account.html)
- [1.3.1. Launching a “Manager Instance”](https://docs.fires.im/en/1.15.1/Initial-Setup/Setting-up-your-Manager-Instance.html#launching-a-manager-instance) _Important:_
 **do not** setup the FireSim Repo yet, as we will be using our fork

_Note to ASPLOS'23 Reviewers:_ You can **skip the above steps** as you will be provided with the `ssh` login credentials
for a manager node that has already been setup for you.

### FireSim Download and MetaSimulation

**Kick the Tires**: Do all but stop the asm tests early.

First we need to download a fork of FireSim onto the manager node.
This fork is based on the `1.15.1` release of FireSim and contains
our extensions which adds support for the `cover` statement to `firesim`.

```{.sh}
git clone https://github.com/ekiwi/firesim.git
cd firesim
git checkout coverage-asplos2023 # make sure we are on the branch with our modifications
./build-setup.sh
```

Running the `build-setup.sh` will take several minutes.

Now we are ready to run a simple "MetaSimulation" to check that things are working:
```{.sh}
# in the firesim directory:
source sourceme-f1-manager.sh
cd sim
# generate FireSim RTL and Verilator simulation binary
make TARGET_CONFIG=CCW32_WithDefaultFireSimBridges_WithFireSimConfigTweaks_chipyard.QuadRocketConfig verilator
# run tests, feel free to cancel (Ctrl + C) after a couple of tests
make TARGET_CONFIG=CCW32_WithDefaultFireSimBridges_WithFireSimConfigTweaks_chipyard.QuadRocketConfig run-asm-tests
```

These commands will generate a FireSim simulation of a RocketChip CPU
with line coverage instrumentation and our scan-chain implementation.
The FireSim RTL is then simulated with several small RISC-V tests
using the open-source Verilator simulator.

During the test simulation you will see status messages from the coverage
implementation, like:

```
[SERIAL] starting to scan out coverage
[COVERAGE] starting to scan
[COVERAGE] done scanning
[COVERAGE] 0.497458s (14.1043% of total 3.527s simulation time) spent scanning out coverage
[SERIAL] done scanning
```

Afterwards you can see coverage counts (`*.cover.json`) in `output/f1/FireSim-FireSimRocketConfig-BaseF1Config/`.
These coverage counts were used together with the `scripts/merge_cov.py` script in this repository to reduce
the number of cover points synthesized for FireSim as described in section 5.3.
Unfortunately this process is _not automated_.

### FireSim Utilization Numbers (Figures 9 and 10)


**Kick the Tires**: Skip. This takes several hours and is only for the full evaluation.

In order to reproduce Figures 9 and 10, we need to setup the firesim manager.
You can find more information on how to enter your AWS credentials [in the FireSim documentation](https://docs.fires.im/en/1.15.1/Initial-Setup/Setting-up-your-Manager-Instance.html#completing-setup-using-the-manager).

```{.sh}
# in a fresh shell in the `firesim` directory:
source sourceme-f1-manager.sh
firesim managerinit --platform f1
```

Now we need to build an FPGA image for our instrumented Rocket and BOOM cores with different coverage counter
widths in order to determine the utilization and `f_max` numbers. Unfortunately the more recent version of Vivado
crashes with a segmentation fault when trying to build the BOOM designs.

We are going to use a four core RocketChip.
We've added build recipes for you in `firesim/deploy/config_build_recipes.yaml`. Instrumented recipes take the form:

```
# 48-bit variant
coverage_rocket_48:
    DESIGN: FireSim
    TARGET_CONFIG: CCW48_WithDefaultFireSimBridges_WithFireSimConfigTweaks_chipyard.QuadRocketConfig
    PLATFORM_CONFIG: WithAutoILA_F90MHz_BaseF1Config
    deploy_triplet: null
    post_build_hook: null
    metasim_customruntimeconfig: null
    bit_builder_recipe: bit-builder-recipes/f1.yaml
```
Whereas baseline, uninstrumented versions are suffixed with `_baseline`, and lack the `CCW<width>_` prefix in their `TARGET_CONFIG`:
```
coverage_rocket_baseline:                                                                           
    DESIGN: FireSim                                                                                 
    TARGET_CONFIG: WithDefaultFireSimBridges_WithFireSimConfigTweaks_chipyard.QuadRocketConfig      
    PLATFORM_CONFIG: WithAutoILA_F90MHz_BaseF1Config                                                
    deploy_triplet: null                                                                            
    post_build_hook: null                                                                           
    metasim_customruntimeconfig: null                                                               
    bit_builder_recipe: bit-builder-recipes/f1.yaml   
```

In order to measure the maximum frequency that can be achieved, we need to tell Vivado to try and generate a design with a high frequency.
We thus provide RocketChip configuration that set the frequency constraint to 140MHz instead of 90MHz in order to trade of high frequency for higher resource usage.
These configurations are suffixed `_f_max`.

We've taken the liberty of adding all 16 builds to your `firesim/deploy/config_build.yaml`. To illustrate the build process, we've commented out all but one of the builds. To build everything in parallel (on 16 z1d.2xlarge instances), uncomment the other listed builds in `builds_to_run`.
 
To start building, run the following command: `firesim buildbitstream`
You will be notified via email once the virtual machine with the RTL design is built. Your bitstream(s), represented as HWDB snippets, will appear in `firesim/deploy/built-hwdb-entries`. You may append these file snippets to `firesim/deploy/config_hwdb.yaml`, overriding the entries we built for you.

The build reports can be found in `firesim/deploy/results-build/<timestamp>-<name>/<tuple>/build/reports/` (where `<timestamp>`, `<name>` and `<tuple>` have been replaced with the appropriate strings). Inside that folder you can find a file ending in `SH_CL_utilization.rpt`.
Note down the number of `Logic LUTs` and the number of `FFs` in the first row of the table and
compare them to the baseline numbers to obtain the data in Figure 9. Remember to use the designs constrained to 90MHz in order to calculate the resource usage.

Note, the reported utilization numbers will differ somewhat from the pubished versions in the paper, since those were built with an earlier version of FireSim and Vivado but the trends should hold. In case there are any problems, you can find more info on building AFIs in
[the FireSim documentation](https://docs.fires.im/en/1.15.1/Building-a-FireSim-AFI.html).

To measure an `fmax` trend over the different counter widths, use the configs constrained to 140MHz with the `_f_max` suffix. Most of your builds will fail -- this is intended. To yield the frequencies in our paper, we took the path with the largest-magnitude negative setup slack in the simulator's primary clock domain and added that to the period we requested. A timing summary for each design can be found in the same report folder above, in a file with a `SH_CL_final_timing_summary.rpt` suffix. In that file, look for the entries that fail to meet timing in the `buildtop_reference_clock` path group under `Max Delay Paths`. See the example below:

```
Max Delay Paths                                                                                     
--------------------------------------------------------------------------------------              
Slack (VIOLATED) :             -0.510ns  (arrival time - required time)                                   
  Source:                 WRAPPER_INST/CL/firesim_top/top/sim/target/FireSim_/lazyModule/system/tile_prci_domain_1/tile_reset_domain_tile/fpuOpt/fpmu/inPipe_bits_in2_reg[37]/C                                               
                            (rising edge-triggered cell FDRE clocked by buildtop_reference_clock  {rise@0.000ns fall@3.571ns period=7.142ns})
  Destination:            WRAPPER_INST/CL/firesim_top/top/sim/target/FireSim_/lazyModule/system/tile_prci_domain_1/tile_reset_domain_tile/fpuOpt/fpmu/io_out_b_data_reg[37]/D
                            (rising edge-triggered cell FDRE clocked by buildtop_reference_clock  {rise@0.000ns fall@3.571ns period=7.142ns})
  Path Group:             buildtop_reference_clock                                                  
  Path Type:              Setup (Max at Slow Process Corner)                                          

```

Here the `Slack` is `-0.510ns` and the `period` is `7.142ns`. This gives a practical `fmax` of
`1 / (7.1421ns - (-0.510ns)) ~= 130 MHz`. To produce the plot in our paper we automated the extraction of this worst case max delay path from these reports.

### Linux Boot Speed

**Kick the Tires**: Skip unless using the pre-compiled bitstreams. 

To smooth over this process, we've pre-compiled a Buildroot linux distribution whose `init` process should call `poweroff` once it starts running. This image was fetched from `S3` when you setup your FireSim repo and can be found in `firesim/deploy/workloads/linux-poweroff/`. 

Full details about running simulations under Firesim can be found [here](https://docs.fires.im/en/1.15.1/Running-Simulations-Tutorial/Running-a-Single-Node-Simulation.html). For your convenience, we've modified the default runtime configuration file (`firesim/deploy/config_runtime.yaml`) to boot the provided buildroot linux distribution on a 16-bit coverage counter Rocket-based design. 

```
# in a fresh shell in the `firesim` directory:
source sourceme-f1-manager.sh

# Requests an FPGA instance
firesim launchrunfarm

# Programs the FPGA with our desired bitstream
firesim infrasetup

# Runs the simulation
firesim runworkload

```

At this point the `firesim` manager should produce a running log showing the current status of your simulation:

```
FireSim Simulation Status @ 2023-02-05 00:26:52.668218
--------------------------------------------------------------------------------
This workload's output is located in:
/home/centos/firesim/deploy/results-workload/2023-02-05--00-25-49-linux-poweroff/
This run's log is located in:
/home/centos/firesim/deploy/logs/2023-02-05--00-25-49-runworkload-P00DOYVXRGM89FBC.log
This status will update every 10s.
--------------------------------------------------------------------------------
Instances
--------------------------------------------------------------------------------
Hostname/IP: 192.168.3.76 | Terminated: False
--------------------------------------------------------------------------------
Simulated Switches
--------------------------------------------------------------------------------
--------------------------------------------------------------------------------
Simulated Nodes/Jobs
--------------------------------------------------------------------------------
Hostname/IP: 192.168.3.76 | Job: linux-poweroff0 | Sim running: False
--------------------------------------------------------------------------------
Summary
--------------------------------------------------------------------------------
1/1 instances are still running.
0/1 simulations are still running.
--------------------------------------------------------------------------------
```

When the simulation is complete, the manager will copy back your results to:
`firesim/deploy/results-workload/<date>-linux-poweroff/linux-poweroff0/`

Of note is the 'uartlog' which will have the console output from linux boot as well as the simulation runtime statistics. The tail of this log
should look as follows, with only small changes in wallclock-related times. 

```
AH00558: httpd: Could not reliably determine the server's fully qualified domain name, using 127.0.1.1. Set the 'ServerName' directive globally to suppress this message
Starting dropbear sshd: OK                                                                          
Cycles elapsed: 924391871                                                                           
Time elapsed: 1.745500000 seconds                                                                   
Powering off immediately.                                                                           
[    1.770936] reboot: Power down                                                                   
[SERIAL] starting to scan out coverage                                                              
[COVERAGE] starting to scan                                                                         
[COVERAGE] done scanning                                                                            
[COVERAGE] 0.035377s (0.0729254% of total 48.5112s simulation time) spent scanning out coverage     
[SERIAL] done scanning                                                                              
                                                                                                    
Simulation complete.                                                                                
*** PASSED *** after 3141466757 cycles                                                              
                                                                                                    
Emulation Performance Summary                                                                       
------------------------------                                                                      
Wallclock Time Elapsed: 48.5 s                                                                      
Host Frequency: 89.998 MHz                                                                          
Target Cycles Emulated: 3141466757                                                                  
Effective Target Frequency: 64.745 MHz                                                              
FMR: 1.39                                                                                           
Note: The latter three figures are based on the fastest target clock.  
```

Please compare these numbers to what we report in our paper:
> We used our instrumented SoCs with 16-bit coverage counters to boot Linux and obtained line coverage results. For the RocketChip design the simulation executed 3.3B cycles in 50.4s (65 MHz). Scanning out the 8060 cover counts at the end of the simulation took 12ms.

With the newer version of FireSim and Linux used in this artifact, the numbers are slightly different,
but the trends should hold.

Feel free to update `firesim/deploy/config_runtime.yaml` to run against one of the other designs, by modifying the `default_hw_config` field to specify one of the other HWDB entries in `firesim/deploy/config_hwdb.yaml`. For each simulation, be sure to run both:
```
firesim infrasetup
firesim runworkload
```

When you're done, release your F1 instance by running:

```
firesim terminaterunfarm
```

Then, double check in your AWS console that the instance has been terminated. 

Unfortunately, due to a bug in Vivado versions 2021.1 and 2021.2 (the only versions readily available on EC2 at time of writing), we were unable to rebuild BOOM images using a modern version of FireSim. The old builds should be reproducible with a local installation of Vivado 2018.3.
