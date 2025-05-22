use std::sync::{Mutex, OnceLock};

/**
 * Copyright (c) 2023 Institute of Computing Technology, Chinese Academy of Sciences
 * xfuzz is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */
extern crate libc;
extern crate rand;

use std::ffi::CString;
use std::io::{self, Write};

use crate::coverage::*;
use crate::monitor::store_testcase;

use libafl::prelude::*;
use libc::*;

unsafe extern "C" {
    pub fn sim_main(argc: c_int, argv: *const *const c_char) -> c_int;

    pub fn get_cover_number() -> c_uint;

    pub fn update_stats(bitmap: *mut c_char);

    pub fn display_uncovered_points();

    pub fn set_cover_feedback(name: *const c_char);

    pub fn enable_sim_verbose();

    pub fn disable_sim_verbose();
}

static SIM_ARGS: OnceLock<Mutex<Vec<String>>> = OnceLock::new();

fn sim_run(workload: &String) -> i32 {
    // prepare the simulation arguments in Vec<String> format
    let mut sim_args: Vec<String> = vec!["emu".to_string(), "-i".to_string(), workload.to_string()]
        .iter()
        .map(|s| s.to_string())
        .collect();
    let guard = SIM_ARGS
        .get()
        .expect("SIM_ARGS not initialized")
        .lock()
        .unwrap();
    sim_args.extend(guard.iter().cloned());

    // convert the simulation arguments into c_char**
    let sim_args: Vec<_> = sim_args
        .iter()
        .map(|s| CString::new(s.as_bytes()).unwrap())
        .collect();
    let mut p_argv: Vec<_> = sim_args.iter().map(|arg| arg.as_ptr()).collect();
    p_argv.push(std::ptr::null());

    // send simulation arguments to sim_main and get the return code
    let ret = unsafe { sim_main(sim_args.len() as i32, p_argv.as_ptr()) };
    unsafe { update_stats(cover_as_mut_ptr()) }
    cover_accumulate();

    ret
}

fn sim_run_from_memory(input: &BytesInput) -> i32 {
    // create a workload-in-memory name for the input bytes
    let wim_bytes = input.mutator_bytes();
    let wim_addr = wim_bytes.as_ptr();
    let wim_size = wim_bytes.len() as u64;
    let wim_name = format!("wim@{wim_addr:p}+0x{wim_size:x}");
    // pass the in-memory workload to sim_run
    sim_run(&wim_name)
}

pub(crate) fn sim_run_multiple(workloads: &Vec<String>, auto_exit: bool) -> i32 {
    let mut ret = 0;
    for workload in workloads.iter() {
        ret = sim_run(workload);
        if ret != 0 {
            println!("{} exits abnormally with return code: {}", workload, ret);
            if auto_exit {
                break;
            }
        }
    }
    return ret;
}

pub static mut USE_RANDOM_INPUT: bool = false;
pub static mut CONTINUE_ON_ERRORS: bool = false;
pub static mut SAVE_ERRORS: bool = false;
pub static mut NUM_RUNS: u64 = 0;
pub static mut MAX_RUNS: u64 = u64::MAX;

pub(crate) fn fuzz_harness(input: &BytesInput) -> ExitKind {
    let ret = if unsafe { USE_RANDOM_INPUT } {
        let random_bytes: Vec<u8> = (0..1024).map(|_| rand::random::<u8>()).collect();
        let b = BytesInput::new(random_bytes);
        sim_run_from_memory(&b)
    } else {
        sim_run_from_memory(input)
    };

    // get coverage
    cover_display();
    io::stdout().flush().unwrap();

    // panic if return code is non-zero (this is for fuzzers to catch crashes)
    let do_panic = unsafe { !CONTINUE_ON_ERRORS && ret != 0 };
    if do_panic {
        unsafe { display_uncovered_points() }
        panic!("<<<<<< Bug triggered >>>>>>");
    }

    // save the target testcase into disk
    let do_save = unsafe { SAVE_ERRORS && ret != 0 };
    if do_save {
        store_testcase(input, &"errors".to_string(), None);
    }

    // panic to exit the fuzzer if max_runs is reached
    unsafe { NUM_RUNS += 1 };
    let do_exit = unsafe { NUM_RUNS >= MAX_RUNS };
    if do_exit {
        println!("Exit due to max_runs == 0");
        unsafe { display_uncovered_points() }
        panic!("Exit due to max_runs == 0");
    }

    ExitKind::Ok
}

pub(crate) fn set_sim_env(
    coverage: String,
    verbose: bool,
    max_runs: Option<u64>,
    emu_args: Vec<String>,
) {
    let cover_name = CString::new(coverage.as_bytes()).unwrap();
    unsafe { set_cover_feedback(cover_name.as_ptr()) }

    if verbose {
        unsafe { enable_sim_verbose() }
    } else {
        unsafe { disable_sim_verbose() }
    }

    if max_runs.is_some() {
        unsafe { MAX_RUNS = max_runs.unwrap() };
    }

    let _ = SIM_ARGS.set(Mutex::new(emu_args));

    cover_init();
}
