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
use crate::harness::get_cover_number;

struct Coverage {
    cover_points: Vec<u8>,
    accumulated: Vec<u8>,
}

impl Coverage {
    pub fn new(n_cover: usize) -> Self {
        Self {
            cover_points: vec![0; n_cover],
            accumulated: vec![0; n_cover],
        }
    }

    pub fn len(&self) -> usize {
        self.cover_points.capacity()
    }

    pub fn as_mut_ptr(&self) -> *mut u8 {
        self.cover_points.as_ptr().cast_mut()
    }

    pub fn accumulate(&mut self) {
        for (i, covered) in self.cover_points.iter().enumerate() {
            if *covered != 0 as u8 {
                self.accumulated[i] = 1;
            }
        }
    }

    pub fn get_accumulative_coverage(&self) -> f64 {
        let mut covered_num: usize = 0;
        for covered in self.accumulated.iter() {
            if *covered != 0 as u8 {
                covered_num += 1;
            }
        }
        100.0 * covered_num as f64 / self.len() as f64
    }

    pub fn display(&self) {
        // println!("Total Covered Points: {:?}", self.accumulated);
        println!(
            "Total Coverage:       {:.3}%",
            self.get_accumulative_coverage()
        );
    }
}

static ICOVERAGE: OnceLock<Mutex<Coverage>> = OnceLock::new();

/// Call this once, right after your C test‑bench has told you how many
/// counters are present.
pub(crate) fn cover_init() {
    let cover = Coverage::new(unsafe { get_cover_number() as usize });
    // `set` returns Err if it was already initialised; handle that however
    // you prefer (here we just ignore the second call).
    let _ = ICOVERAGE.set(Mutex::new(cover));
}

fn cov() -> std::sync::MutexGuard<'static, Coverage> {
    ICOVERAGE
        .get()
        .expect("cover_init() not called")
        .lock()
        .expect("poisoned mutex")
}

pub(crate) fn cover_len() -> usize {
    cov().len()
}

pub(crate) fn cover_as_mut_ptr() -> *mut u8 {
    let guard = cov();
    guard.as_mut_ptr().cast::<u8>()
}

pub(crate) fn cover_accumulate() {
    cov().accumulate()
}

pub(crate) fn cover_display() {
    cov().display()
}
