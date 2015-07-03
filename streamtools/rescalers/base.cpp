#include "rescalers/public.hpp"
#include "rescalers/factory.hpp"
#include <map>
#include <vector>
#include <stdexcept>
#include <sstream>
#include <cstdlib>

#define DEFAULT_RESCALER "lanczos2"

namespace
{
	std::map<std::string, rescaler_factory*>* factories;
}

namespace
{
	class composite_rescaler_failure : public std::runtime_error
	{
	public:
		composite_rescaler_failure(const std::string& what)
			: std::runtime_error(what)
		{
		}
	};
}

rescaler::~rescaler()
{
}


rescaler_factory::~rescaler_factory()
{
}

rescaler_factory::rescaler_factory(const std::string& type)
{
	if(!factories)
		factories = new std::map<std::string, rescaler_factory*>();
	(*factories)[type] = this;
}

rescaler& rescaler::make(const std::string& type)
{
	if(!factories || !factories->count(type)) {
		std::ostringstream str;
		str << "Unknown rescaler type '" << type << "'";
		throw std::runtime_error(str.str());
	}
	return (*factories)[type]->make(type);
}

std::string get_rescaler_string()
{
	bool first = true;
	if(!factories)
		return "";
	std::string c;
	std::map<std::string, rescaler_factory*>& f = *factories;
	for(auto i = f.begin(); i != f.end(); ++i) {
		if(first)
			c = i->first;
		else
			c = c + " " + i->first;
		first = false;
	}
	return c;
}

std::list<std::string> get_rescaler_list()
{
	std::list<std::string> l;
	std::map<std::string, rescaler_factory*>& f = *factories;
	for(auto i = f.begin(); i != f.end(); ++i)
		l.push_back(i->first);
	return l;
}

namespace
{
	class composite_rescaler_c : public rescaler
	{
	public:
		composite_rescaler_c(rescaler& _s1, uint32_t _iwidth, uint32_t _iheight, rescaler& _s2);
		~composite_rescaler_c();
		void operator()(uint8_t* target, uint32_t twidth, uint32_t theight,
			const uint8_t* source, uint32_t swidth, uint32_t sheight);
	private:
		rescaler& s1;
		rescaler& s2;
		uint32_t iwidth;
		uint32_t iheight;
	};

	composite_rescaler_c::composite_rescaler_c(rescaler& _s1, uint32_t _iwidth, uint32_t _iheight, rescaler& _s2)
		: s1(_s1), s2(_s2)
	{
		iwidth = _iwidth;
		iheight = _iheight;
	}

	composite_rescaler_c::~composite_rescaler_c()
	{
		delete &s1;
		delete &s2;
	}

	void composite_rescaler_c::operator()(uint8_t* target, uint32_t twidth, uint32_t theight,
		const uint8_t* source, uint32_t swidth, uint32_t sheight)
	{
		std::vector<uint8_t> intermediate(4 * (size_t)iwidth * iheight);
		try {
			s1(&intermediate[0], iwidth, iheight, source, swidth, sheight);
		} catch(composite_rescaler_failure& e) {
			throw;
		} catch(std::exception& e) {
			std::ostringstream str;
			str << "Failed to rescale from " << swidth << "*" << sheight << " to " << iwidth << "*"
				<< iheight << ": " << e.what();
			throw composite_rescaler_failure(str.str());
		}
		try {
			s2(target, twidth, theight, &intermediate[0], iwidth, iheight);
		} catch(composite_rescaler_failure& e) {
			throw;
		} catch(std::exception& e) {
			std::ostringstream str;
			str << "Failed to rescale from " << iwidth << "*" << iheight << " to " << twidth << "*"
				<< theight << ": " << e.what();
			throw composite_rescaler_failure(str.str());
		}
	}
}

rescaler& composite_rescaler(rescaler& s1, uint32_t iwidth, uint32_t iheight, rescaler& s2)
{
	return *new composite_rescaler_c(s1, iwidth, iheight, s2);
}

rescaler_group::rescaler_group(rescaler& _default_rescaler)
{
	default_rescaler = &_default_rescaler;
}

rescaler_group::~rescaler_group()
{
	delete default_rescaler;
	for(auto i = special_rescalers.begin(); i != special_rescalers.end(); ++i)
		delete(i->second);
}

void rescaler_group::set_default_rescaler(rescaler& _default_rescaler)
{
	if(default_rescaler == &_default_rescaler)
		return;
	delete default_rescaler;
	default_rescaler = &_default_rescaler;
}

void rescaler_group::set_special_rescaler(uint32_t width, uint32_t height, rescaler& rescaler)
{
	std::pair<uint32_t, uint32_t> key = std::make_pair(width, height);
	if(special_rescalers.count(key) && special_rescalers[key] != &rescaler)
		delete special_rescalers[key];
	special_rescalers[key] = &rescaler;
}

void rescaler_group::operator()(uint8_t* target, uint32_t twidth, uint32_t theight,
	const uint8_t* source, uint32_t swidth, uint32_t sheight)
{
	std::pair<uint32_t, uint32_t> key = std::make_pair(swidth, sheight);
	try {
		if(special_rescalers.count(key))
			(*special_rescalers[key])(target, twidth, theight, source, swidth, sheight);
		else
			(*default_rescaler)(target, twidth, theight, source, swidth, sheight);
	} catch(composite_rescaler_failure& e) {
		throw;
	} catch(std::exception& e) {
		std::ostringstream str;
		str << "Failed to rescale from " << swidth << "*" << sheight << " to " << twidth << "*"
			<< theight << ": " << e.what();
		throw std::runtime_error(str.str());
	}
}

namespace
{
	rescaler* chain_rescalers(rescaler* previous, std::string nscaler, uint32_t width, uint32_t height)
	{
		if(!previous)
			return &rescaler::make(nscaler);
		return &composite_rescaler(rescaler::make(nscaler), width, height, *previous);
	}
}

struct parsed_scaler parse_rescaler_expression(const std::string& expr)
{
	std::string _expr = expr;
	struct parsed_scaler s;
	s.use_rescaler = NULL;
	s.is_special = false;
	std::string rescaler_name;
	s.swidth = 0;
	s.sheight = 0;
	unsigned state = 0;	//Number of words mod 3.
	size_t baseoff = 0;	//Next position to process.
	char* x;
	while(baseoff != std::string::npos) {
		size_t nextspace = _expr.find(' ', baseoff);
		//Split the word.
		std::string word;
		if(nextspace == std::string::npos) {
			word = _expr.substr(baseoff);
			baseoff = nextspace;
		} else {
			word = _expr.substr(baseoff, nextspace - baseoff);
			baseoff = nextspace + 1;
		}
		if(word == "")
			continue;	//Ignore empty words.
		switch(state % 3) {
		case 0:
			//This is rescaler name.
			rescaler_name = word;
			s.use_rescaler = chain_rescalers(s.use_rescaler, rescaler_name, s.swidth, s.sheight);
			s.is_special = false;
			break;
		case 1:
			//Width.
			s.swidth = strtoul(word.c_str(), &x, 10);
			if(*x || !s.swidth) {
				std::ostringstream str;
				str << "Bad width '" << word << "' for scaler.";
				throw std::runtime_error(str.str());
			}
			break;
		case 2:
			//Height.
			s.sheight = strtoul(word.c_str(), &x, 10);
			if(*x || !s.sheight) {
				std::ostringstream str;
				str << "Bad height '" << word << "' for scaler.";
				throw std::runtime_error(str.str());
			}
			s.is_special = true;
			break;
		}
		state++;
	}
	if(state == 0)
		throw std::runtime_error("Empty rescaler specification not allowed.");
	if(state % 3 == 2)
		throw std::runtime_error("With and height must be specified together");
	return s;
}

rescaler& get_default_rescaler()
{
	return *(parse_rescaler_expression(DEFAULT_RESCALER).use_rescaler);
}

