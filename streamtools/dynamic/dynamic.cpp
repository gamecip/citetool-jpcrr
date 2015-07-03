#include "dynamic/dynamic.hpp"
#include <iostream>
#include <cstdarg>
#include <cstring>
#include <cstdio>

#if !defined(_WIN32) && !defined(_WIN64)
#define _USE_BSD
#include <unistd.h>
#include <sys/mman.h>
#endif

void* commit_machine_code(const std::vector<uint8_t>& code)
{
	uint8_t* cb = NULL;
	if(code.empty())
		return NULL;
#ifdef __linux__
	uint32_t toalloc = (code.size() + getpagesize() - 1) / getpagesize() * getpagesize();
	cb = (uint8_t*)mmap(NULL, toalloc, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	if(cb == MAP_FAILED)
		return NULL;
	memcpy(cb, &code[0], code.size());
	if(mprotect(cb, toalloc, PROT_READ | PROT_EXEC) < 0) {
		munmap(cb, toalloc);
		return NULL;
	}
#endif
	fprintf(stderr, "Loaded at %p\n", cb);
	return cb;
}

#define FLAG_IMM32 1
#define FLAG_IMMWORD 2
#define FLAG_IMMWORDNEG 3
#define FLAG_IMMASK 3
#define END_INSTRUCTION 0x100
#define PREFIX64 0x101
#define IMM32 0x102
#define IMMWORD 0x103
#define IMMWORDNEG 0x104
#define ONLY32 0x105
#define ONLY64 0x106

class instruction_writer
{
public:
	instruction_writer(std::vector<uint8_t>& _code, bool _bits64)
		: code(_code), bits64(_bits64)
	{
	}

	size_t size()
	{
		return code.size();
	}

	void write(uint32_t first, ...)
	{
		va_list args;
		uint32_t op = first;
		va_start(args, first);
		do {
			if(op == END_INSTRUCTION)
				return;
			if(op == ONLY32 && bits64)
				return;
			if(op == ONLY64 && !bits64)
				return;
			if(!(op & 0xFFFFFF00))
				code.push_back((uint8_t)op);
			else if(op == PREFIX64 && bits64)
				code.push_back((uint8_t)0x48);
			else if(op == IMM32) {
				uint32_t value = va_arg(args, uint32_t);
				code.push_back(value);
				code.push_back(value >> 8);
				code.push_back(value >> 16);
				code.push_back(value >> 24);
			} else if(op == IMMWORD || op == IMMWORDNEG) {
				uint32_t value = va_arg(args, uint32_t);
				code.push_back(value);
				code.push_back(value >> 8);
				code.push_back(value >> 16);
				code.push_back(value >> 24);
				if(bits64) {
					code.push_back((op == IMMWORDNEG) ? 0xFF : 0x00);
					code.push_back((op == IMMWORDNEG) ? 0xFF : 0x00);
					code.push_back((op == IMMWORDNEG) ? 0xFF : 0x00);
					code.push_back((op == IMMWORDNEG) ? 0xFF : 0x00);
				}
			}
		} while((op = va_arg(args, uint32_t)) != END_INSTRUCTION);
	}
private:
	std::vector<uint8_t>& code;
	bool bits64;
};

//Trashes [ER]CX, [ER]SI and [ER]DI. Pops stuff from stack.
void write_line_intel(instruction_writer& code, uint32_t* strip_widths, uint32_t swidth, uint32_t twidth)
{
	for(uint32_t i = 0; i < swidth; i++)
	{
		//LODSD.
		code.write(0xAD, END_INSTRUCTION);
		for(uint32_t j = 0; j < strip_widths[i]; j++)
			//STOSD
			code.write(0xAB, END_INSTRUCTION);
	}
	//POP ECX / POP RCX; PUSH RSI / PUSH ESI ; MOV ESI, EDI / MOV RSI, RDI, SUB ESI, imm32 / SUB RSI, imm32 ;
	//REP MOVSD; POP ESI / POP RSI
	code.write(0x59, 0x56, PREFIX64, 0x89, 0xFE, PREFIX64, 0x81, 0xEE, IMM32, 4 * twidth, 0xF3, 0xA5, 0x5E,
		END_INSTRUCTION);
}

void write_loop_intel(instruction_writer& code, uint32_t* strip_widths, uint32_t swidth, uint32_t twidth,
	uint32_t* strip_heights, uint32_t lines)
{
	//PUSHF; CLD
	code.write(0x9C, 0xFC, END_INSTRUCTION);

	for(uint32_t i = lines - 1; i < lines; i--)
		//MOV EAX, imm32 / MOV RAX, imm64 ; PUSH EAX / PUSH RAX
		code.write(PREFIX64, 0xB8, IMMWORD, (strip_heights[i] - 1) * twidth, 0x50, END_INSTRUCTION);

	//MOV EDX, imm32 / MOV RDX, imm64
	code.write(PREFIX64, 0xBA, IMMWORD, lines, END_INSTRUCTION);

	uint32_t osize = code.size();
	write_line_intel(code, strip_widths, swidth, twidth);
	//SUB EDX, 1 / SUB RDX, 1
	code.write(PREFIX64, 0x83, 0xEA, 0x01, END_INSTRUCTION);

	//JNZ NEAR imm ; POPF
	code.write(0x0F, 0x85, IMM32, osize - (code.size() + 6), 0x9D, END_INSTRUCTION);
}

void prologue_linux_intel(instruction_writer& code)
{
	//PUSH EBP / PUSH RBP; MOV EBP, ESP / MOV RBP, RSP ; (only32) MOV EDI, [EBP + 8] ; MOV EDI, [EBP + 12]
	code.write(0x55, PREFIX64, 0x89, 0xE5, ONLY32, 0x8B, 0x7D, 0x08, 0x8B, 0x75, 0x0C, END_INSTRUCTION);

}

void postlogue_linux_intel(instruction_writer& code)
{
	//LEAVE ; RET
	code.write(0xC9, 0xC3, END_INSTRUCTION);
}


void generate_hdscaler(std::vector<uint8_t>& code, uint32_t* strip_widths, uint32_t* strip_heights, uint32_t swidth,
	uint32_t sheight, uint32_t twidth)
{
#if defined(__x86_64__)
#if defined(__linux__)
	instruction_writer writer(code, true);
	prologue_linux_intel(writer);
	write_loop_intel(writer, strip_widths, swidth, twidth, strip_heights, sheight);
	postlogue_linux_intel(writer);
#else
	return;
#endif
#else
#if defined(__i386__)
#if defined(__linux__)
	instruction_writer writer(code, false);
	prologue_linux_intel(writer);
	write_loop_intel(writer, strip_widths, swidth, twidth, strip_heights, sheight);
	postlogue_linux_intel(writer);
#else
	return;
#endif
#else
	return;
#endif
#endif
}
